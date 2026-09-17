package eu.kanade.tachiyomi.extension.ko.newxtoon

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList

open class UriPartFilter(displayName: String, private val vals: Array<Pair<String, String>>) : Filter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {

    fun toUriPart(): String = vals[state].second
}

class CategoryFilter :
    UriPartFilter(
        "카테고리",
        arrayOf(
            "일반만화" to "일반만화",
            "BL·GL" to "BL·GL",
            "성인만화" to "성인",
        ),
    )

class SortFilter :
    UriPartFilter(
        "정렬",
        arrayOf(
            "최신순" to "",
            "인기순" to "popular",
        ),
    )

class WeekdayFilter :
    UriPartFilter(
        "요일",
        arrayOf(
            "전체" to "",
            "월요일" to "월",
            "화요일" to "화",
            "수요일" to "수",
            "목요일" to "목",
            "금요일" to "금",
            "토요일" to "토",
            "일요일" to "일",
        ),
    )

class StatusFilter :
    UriPartFilter(
        "연재 상태",
        arrayOf(
            "전체" to "",
            "연재중" to "연재중",
            "완결" to "완결",
        ),
    )

class PlatformFilter :
    UriPartFilter(
        "플랫폼",
        arrayOf(
            "전체" to "",
            "카카오페이지" to "kakao-page",
            "네이버" to "naver",
            "레진" to "lezhin",
            "리디" to "ridi",
            "탑툰" to "toptoon",
            "봄툰" to "bomtoon",
            "MR블루" to "mrblue",
            "투믹스" to "toomics",
            "피너툰" to "peanutoon",
            "코미코" to "comico",
        ),
    )

class GenreFilter : UriPartFilter("장르", GENRES)

fun filters(): FilterList = FilterList(
    Filter.Header("카테고리별 장르는 사이트의 인기 태그 기준입니다"),
    CategoryFilter(),
    SortFilter(),
    WeekdayFilter(),
    StatusFilter(),
    PlatformFilter(),
    GenreFilter(),
)

/**
 * Merged genre tags across all categories (일반만화 / BL·GL / 성인만화).
 * Display name to genre ID, deduplicated by ID.
 */
val GENRES: Array<Pair<String, String>> = arrayOf(
    "로맨스" to "1",
    "드라마" to "4",
    "판타지" to "2",
    "로맨스판타지" to "2739",
    "성장물" to "2753",
    "액션" to "3",
    "능력녀" to "2902",
    "소설원작" to "2774",
    "왕족/귀족" to "2777",
    "다정남" to "2904",
    "먼치킨" to "2772",
    "로맨틱코미디" to "2903",
    "능력남" to "2905",
    "완결로맨스" to "3266",
    "달달물" to "2771",
    "개그/코미디" to "6",
    "성장" to "2874",
    "복수" to "2754",
    "무협/사극" to "2743",
    "빙의" to "2757",
    "BL" to "2788",
    "현대물" to "2751",
    "성인 (BL)" to "2782",
    "다정공" to "2791",
    "짝사랑" to "2764",
    "미인공" to "2796",
    "학원/캠퍼스" to "2745",
    "능글공" to "2802",
    "집착공" to "2792",
    "고수위" to "2783",
    "상처수" to "2799",
    "순정공" to "2803",
    "다정수" to "2800",
    "대형견공" to "2804",
    "재회" to "2765",
    "미인수" to "2797",
    "강공" to "2793",
    "삼각관계" to "2768",
    "첫사랑" to "2763",
    "한국BL" to "3201",
    "현대극" to "3202",
    "오피스" to "2752",
    "하렘/역하렘" to "2786",
    "미남공" to "3067",
    "하렘" to "2813",
    "유부녀" to "2814",
    "노벨피아원작" to "2816",
    "미시" to "2819",
    "여사친" to "2821",
    "능욕" to "2822",
)

/** Genre display names, used for the blocked-genres preference. */
val GENRE_NAMES: List<String> = GENRES.map { it.first }
