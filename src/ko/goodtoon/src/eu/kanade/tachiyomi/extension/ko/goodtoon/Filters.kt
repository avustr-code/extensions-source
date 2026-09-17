package eu.kanade.tachiyomi.extension.ko.goodtoon

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList

class SelectFilterOption(val name: String, val value: String)

abstract class SelectFilter(
    name: String,
    private val options: List<SelectFilterOption>,
    default: Int = 0,
) : Filter.Select<String>(name, options.map { it.name }.toTypedArray(), default) {
    val selected: String
        get() = options[state].value
}

class GenreFilter(options: List<SelectFilterOption>, default: Int) : SelectFilter("Genre", options, default)
class StatusFilter(options: List<SelectFilterOption>, default: Int) : SelectFilter("Status", options, default)
class DayFilter(options: List<SelectFilterOption>, default: Int) : SelectFilter("Day", options, default)
class PlatformFilter(options: List<SelectFilterOption>, default: Int) : SelectFilter("Platform", options, default)

internal val genreList = listOf(
    SelectFilterOption("전체", ""),
    SelectFilterOption("일반웹툰", "일반웹툰"),
    SelectFilterOption("BL/GL", "BL/GL"),
    SelectFilterOption("성인웹툰", "성인웹툰"),
    SelectFilterOption("학원", "학원"),
    SelectFilterOption("액션", "액션"),
    SelectFilterOption("SF", "SF"),
    SelectFilterOption("스토리", "스토리"),
    SelectFilterOption("판타지", "판타지"),
    SelectFilterOption("BL", "BL"),
    SelectFilterOption("개그", "개그"),
    SelectFilterOption("연애", "연애"),
    SelectFilterOption("드라마", "드라마"),
    SelectFilterOption("로맨스", "로맨스"),
    SelectFilterOption("시대극", "시대극"),
    SelectFilterOption("스포츠", "스포츠"),
    SelectFilterOption("일상", "일상"),
    SelectFilterOption("추리", "추리"),
    SelectFilterOption("공포", "공포"),
    SelectFilterOption("성인", "성인"),
    SelectFilterOption("옴니버스", "옴니버스"),
    SelectFilterOption("에피소드", "에피소드"),
    SelectFilterOption("무협", "무협"),
    SelectFilterOption("소년", "소년"),
    SelectFilterOption("기타", "기타"),
    SelectFilterOption("노벨피아", "노벨피아"),
    SelectFilterOption("유부녀", "유부녀"),
    SelectFilterOption("하드코어", "하드코어"),
    SelectFilterOption("조교", "조교"),
    SelectFilterOption("고수위", "고수위"),
    SelectFilterOption("능욕", "능욕"),
    SelectFilterOption("하렘", "하렘"),
    SelectFilterOption("강제", "강제"),
    SelectFilterOption("여성인기", "여성인기"),
    SelectFilterOption("남성인기", "남성인기"),
    SelectFilterOption("3P", "3P"),
    SelectFilterOption("후방주의", "후방주의"),
    SelectFilterOption("백합", "백합"),
)

internal val statusList = listOf(
    SelectFilterOption("전체", ""),
    SelectFilterOption("연재중", "연재중"),
    SelectFilterOption("완결", "완결"),
)

internal val dayList = listOf(
    SelectFilterOption("전체", ""),
    SelectFilterOption("월", "월"),
    SelectFilterOption("화", "화"),
    SelectFilterOption("수", "수"),
    SelectFilterOption("목", "목"),
    SelectFilterOption("금", "금"),
    SelectFilterOption("토", "토"),
    SelectFilterOption("일", "일"),
    SelectFilterOption("열흘", "열흘"),
)

internal val platformList = listOf(
    SelectFilterOption("전체", ""),
    SelectFilterOption("네이버", "네이버"),
    SelectFilterOption("다음", "다음"),
    SelectFilterOption("카카오", "카카오"),
    SelectFilterOption("레진", "레진"),
    SelectFilterOption("투믹스", "투믹스"),
    SelectFilterOption("탑툰", "탑툰"),
    SelectFilterOption("리디", "리디"),
    SelectFilterOption("코미카", "코미카"),
    SelectFilterOption("배틀코믹스", "배틀코믹스"),
    SelectFilterOption("코믹GT", "코믹GT"),
    SelectFilterOption("케이툰", "케이툰"),
    SelectFilterOption("애니툰", "애니툰"),
    SelectFilterOption("폭스툰", "폭스툰"),
    SelectFilterOption("피너툰", "피너툰"),
    SelectFilterOption("봄툰", "봄툰"),
    SelectFilterOption("코미코", "코미코"),
    SelectFilterOption("무툰", "무툰"),
    SelectFilterOption("기타", "기타"),
)

fun getFilters(): FilterList = FilterList(
    Filter.Header("GoodToon Filters"),
    Filter.Separator(),
    GenreFilter(genreList, 0),
    StatusFilter(statusList, 0),
    DayFilter(dayList, 0),
    PlatformFilter(platformList, 0),
)
