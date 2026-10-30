import itertools
import json
import os
import re
import subprocess
import sys
from pathlib import Path

UPSTREAM_REF = os.getenv("UPSTREAM_REF")

# Fork-only extensions allowed to be built, released and kept in the delivery
# index. Everything else (e.g. modules that merely drifted from upstream in a
# sync window) must never be rebuilt or republished. Set in build_publish.yml.
ALLOWED_MODULES = os.getenv("ALLOWED_MODULES")

EXTENSION_REGEX = re.compile(r"^src/(?P<lang>\w+)/(?P<extension>\w+)")
MULTISRC_LIB_REGEX = re.compile(r"^lib-multisrc/(?P<multisrc>\w+)")
LIB_REGEX = re.compile(r"^lib/(?P<lib>\w+)")
MODULE_REGEX = re.compile(r"^:src:(?P<lang>\w+):(?P<extension>\w+)$")
PKG_NAME_REGEX = re.compile(r"""pkgName\s*=\s*["']([^"']+)["']""")
CORE_FILES_REGEX = re.compile(
    r"^(common/|compiler/|core/|gradle/|build\.gradle\.kts|gradle\.properties|settings\.gradle\.kts)"
)

def run_command(command: str) -> str:
    result = subprocess.run(command, capture_output=True, text=True, shell=True)
    if result.returncode != 0:
        print(result.stderr.strip())
        sys.exit(result.returncode)
    return result.stdout.strip()


def get_allowed() -> tuple[set[str], set[str]] | None:
    """return (module ids, pkg suffixes) the fork is allowed to release.

    Returns None when no allowlist is configured, leaving the caller's
    behaviour unchanged (used by the PR-check workflow).
    """
    if not ALLOWED_MODULES:
        return None
    module_ids = set()
    suffixes = set()
    for token in ALLOWED_MODULES.replace(",", " ").split():
        lang, extension = token.strip().split("/", 1)
        module_ids.add(f":src:{lang}:{extension}")
        suffixes.add(f"{lang}.{extension}")
    return module_ids, suffixes


def resolve_module_suffix(ref: str, lang: str, extension: str) -> str:
    """
    returns the effective applicationId suffix of a module: the DSL override
    (pkgName) if set, else the directory-derived default
    """
    build_file = Path("src", lang, extension, "build.gradle.kts")
    content = None
    if build_file.is_file():
        content = build_file.read_text("utf-8")
    elif ref:
        # the module was deleted; read its build file from the base ref
        result = subprocess.run(
            f"git show {ref}:src/{lang}/{extension}/build.gradle.kts",
            capture_output=True,
            text=True,
            shell=True,
            check=False,
        )
        if result.returncode == 0:
            content = result.stdout

    if content:
        match = PKG_NAME_REGEX.search(content)
        if match:
            return match.group(1)

    return f"{lang}.{extension}"


def resolve_dependent_libs(libs: set[str]) -> set[str]:
    """
    returns all libs which depend on any of the passed libs (/lib),
    recursively resolving transitive dependencies
    """
    if not libs:
        return set()

    all_dependent_libs = set()
    to_process = set(libs)

    while to_process:
        current_libs = to_process
        to_process = set()

        lib_dependency = re.compile(
            rf"project\([\"']:(?:lib):({'|'.join(map(re.escape, current_libs))})[\"']\)"
        )

        for lib in Path("lib").iterdir():
            if lib.name in all_dependent_libs or lib.name in libs:
                continue

            build_file = lib / "build.gradle.kts"
            if not build_file.is_file():
                continue

            content = build_file.read_text("utf-8")

            if lib_dependency.search(content):
                all_dependent_libs.add(lib.name)
                to_process.add(lib.name)

    return all_dependent_libs


def resolve_multisrc_lib(libs: set[str]) -> set[str]:
    """
    returns all multisrc which depend on any of the
    passed libs (/lib)
    """
    if not libs:
        return set()

    lib_dependency = re.compile(
        rf"project\([\"']:(?:lib):({'|'.join(map(re.escape, libs))})[\"']\)"
    )

    multisrcs = set()

    for multisrc in Path("lib-multisrc").iterdir():
        build_file = multisrc / "build.gradle.kts"
        if not build_file.is_file():
            continue

        content = build_file.read_text("utf-8")

        if (lib_dependency.search(content)):
            multisrcs.add(multisrc.name)

    return multisrcs

def resolve_ext(multisrcs: set[str], libs: set[str]) -> set[tuple[str, str]]:
    """
    returns all extensions which depend on any of the
    passed multisrcs or libs
    """
    if not multisrcs and not libs:
        return set()

    multisrc_pattern = '|'.join(map(re.escape, multisrcs)) if multisrcs else None
    lib_pattern = '|'.join(map(re.escape, libs)) if libs else None

    patterns = []
    if multisrc_pattern:
        patterns.append(rf"theme\s*=\s*['\"]({multisrc_pattern})['\"]")
    if lib_pattern:
        patterns.append(rf"project\([\"']:(?:lib):({lib_pattern})[\"']\)")

    regex = re.compile('|'.join(patterns))

    extensions = set()

    for lang in Path("src").iterdir():
        for extension in lang.iterdir():
            build_file = extension / "build.gradle.kts"
            if not build_file.is_file():
                continue

            content = build_file.read_text("utf-8")

            if regex.search(content):
                extensions.add((lang.name, extension.name))

    return extensions

def filter_ours_only(diff_output: list[str], upstream_ref: str) -> list[str]:
    """
    keep only changes that are genuinely ours: a modified/added path is
    ours only when it differs in content from upstream (synced copies are
    byte-identical and must not be rebuilt/released). Renames are checked
    against their new path; deletions are always kept (removing a module
    that is not in the fork's index is a no-op, and user-initiated
    deletions must still be released).
    """
    kept = []
    for line in diff_output:
        status, *parts = line.split("\t", 2)
        if not parts:
            continue
        path = parts[1] if len(parts) == 2 and status.startswith("R") else parts[0]
        if status.startswith(("A", "M", "R")):
            exists = (
                subprocess.run(
                    f"git cat-file -e HEAD:{path}",
                    shell=True,
                    capture_output=True,
                ).returncode
                == 0
            )
            if not exists:
                continue
            identical = (
                subprocess.run(
                    f"git diff --quiet HEAD {upstream_ref} -- {path}",
                    shell=True,
                    capture_output=True,
                ).returncode
                == 0
            )
            if identical:
                continue
        kept.append(line)
    return kept


def get_module_list(ref: str) -> tuple[list[str], list[str], list[str]]:
    diff_output = run_command(f"git diff --name-status {ref}").splitlines()

    if UPSTREAM_REF:
        diff_output = filter_ours_only(diff_output, UPSTREAM_REF)

    changed_files = [
        file
        for line in diff_output
        for file in line.split("\t", 2)[1:]
    ]

    modules = set()
    multisrcs = set()
    libs = set()
    deleted = set()
    core_files_changed = False

    for file in map(lambda x: Path(x).as_posix(), changed_files):
        if CORE_FILES_REGEX.search(file):
            core_files_changed = True

        elif match := EXTENSION_REGEX.search(file):
            lang = match.group("lang")
            extension = match.group("extension")
            if Path("src", lang, extension).is_dir():
                modules.add(f':src:{lang}:{extension}')
            deleted.add(resolve_module_suffix(ref, lang, extension))

        elif match := MULTISRC_LIB_REGEX.search(file):
            multisrc = match.group("multisrc")
            if Path("lib-multisrc", multisrc).is_dir():
                multisrcs.add(multisrc)

        elif match := LIB_REGEX.search(file):
            lib = match.group("lib")
            if Path("lib", lib).is_dir():
                libs.add(lib)

    if core_files_changed:
        (all_modules, all_deleted) = get_all_modules(ref)

        # update existing set so we include deleted extensions
        modules.update(all_modules)
        deleted.update(all_deleted)
        modules, deleted = apply_allowlist(ref, modules, deleted)

        return sorted(modules), sorted(deleted), get_all_lint_modules()

    # Resolve libs that depend on the changed libs (recursively)
    libs.update(
        resolve_dependent_libs(libs)
    )

    # Resolve multisrcs that depend on the changed libs
    multisrcs.update(
        resolve_multisrc_lib(libs)
    )

    # Resolve extensions that depend on the changed multisrcs or libs
    extensions = resolve_ext(multisrcs, libs)
    modules.update([f":src:{lang}:{extension}" for lang, extension in extensions])
    deleted.update([resolve_module_suffix(ref, lang, extension) for lang, extension in extensions])

    lint_modules = {
        *(f":lib:{lib}" for lib in libs),
        *(f":lib-multisrc:{multisrc}" for multisrc in multisrcs),
    }

    modules, deleted = apply_allowlist(ref, modules, deleted)

    return sorted(modules), sorted(deleted), sorted(lint_modules)

def get_all_modules(ref: str) -> tuple[list[str], list[str]]:
    modules = []
    deleted = []
    for lang in Path("src").iterdir():
        for extension in lang.iterdir():
            modules.append(f":src:{lang.name}:{extension.name}")
            deleted.append(resolve_module_suffix(ref, lang.name, extension.name))
    return modules, deleted


def apply_allowlist(ref: str, modules: set[str], deleted: set[str]) -> tuple[set[str], set[str]]:
    """restrict release candidates to the configured fork-only modules.

    Anything outside the allowlist is never built and is additionally dropped
    from the delivery index, so upstream drift can neither be republished nor
    linger in the delivery repo.
    """
    allowed = get_allowed()
    if not allowed:
        return modules, deleted
    allowed_ids, allowed_suffixes = allowed

    filtered_modules = {m for m in modules if m in allowed_ids}

    filtered_deleted = {d for d in deleted if d in allowed_suffixes}
    for lang in Path("src").iterdir():
        for extension in lang.iterdir():
            if not (extension / "build.gradle.kts").is_file():
                continue
            suffix = f"{lang.name}.{extension.name}"
            if suffix not in allowed_suffixes:
                filtered_deleted.add(
                    resolve_module_suffix(ref, lang.name, extension.name)
                )

    return filtered_modules, filtered_deleted


def get_all_lint_modules() -> list[str]:
    modules = [":core"]
    modules.extend(
        f":{directory}:{module.name}"
        for directory in ("lib", "lib-multisrc")
        for module in Path(directory).iterdir()
        if (module / "build.gradle.kts").is_file()
    )
    return sorted(modules)


def create_matrix(modules: list[str]) -> dict:
    return {
        "chunk": [
            {"number": i + 1, "modules": chunk}
            for i, chunk in enumerate(itertools.batched(
                modules,
                int(os.getenv("CI_CHUNK_SIZE", 65))
            ))
        ]
    }


def main() -> None:
    _, ref = sys.argv
    modules, deleted, lint_modules = get_module_list(ref)

    matrix = create_matrix(modules)

    print(
        f"Module chunks to build:\n{json.dumps(matrix, indent=2)}\n\n"
        f"Modules to lint:\n{json.dumps(lint_modules, indent=2)}\n\n"
        f"Module to delete:\n{json.dumps(deleted, indent=2)}"
    )

    if os.getenv("CI") == "true":
        with open(os.getenv("GITHUB_OUTPUT"), 'a') as out_file:
            out_file.write(f"matrix={json.dumps(matrix)}\n")
            out_file.write(f"lint_modules={json.dumps(lint_modules)}\n")
            out_file.write(f"delete={json.dumps(deleted)}\n")

if __name__ == '__main__':
    main()
