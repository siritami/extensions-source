package eu.kanade.tachiyomi.extension.vi.luvevaland

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList

internal class GenreFilter :
    Filter.Group<Filter.CheckBox>(
        "Tag",
        GENRE_OPTIONS.map { Filter.CheckBox(it.first, false) },
    ) {
    fun selectedValues(): List<String> = state
        .mapIndexedNotNull { index, checkBox ->
            if (checkBox.state) {
                GENRE_OPTIONS[index].second
            } else {
                null
            }
        }
}

internal class StatusFilter :
    UriPartFilterNullable(
        "Trạng thái",
        STATUS_OPTIONS,
    )

internal class SortByFilter :
    UriPartFilter(
        "Sắp xếp",
        SORT_BY_OPTIONS,
    )

internal class SortOrderFilter :
    UriPartFilter(
        "Thứ tự",
        SORT_ORDER_OPTIONS,
    )

internal abstract class UriPartFilter(
    displayName: String,
    private val options: List<Pair<String, String>>,
) : Filter.Select<String>(displayName, options.map { it.first }.toTypedArray()) {
    fun toUriPart(): String = options[state].second
}

internal abstract class UriPartFilterNullable(
    displayName: String,
    private val options: List<Pair<String, String?>>,
) : Filter.Select<String>(displayName, options.map { it.first }.toTypedArray()) {
    fun toUriPart(): String? = options[state].second
}

internal fun getFilters(): FilterList = FilterList(
    GenreFilter(),
    StatusFilter(),
    SortByFilter(),
    SortOrderFilter(),
)

private val GENRE_OPTIONS = listOf(
    Pair("\"Đặc Sắc\"", "107"),
    Pair("1Vs1", "72"),
    Pair("419", "75"),
    Pair("Bách hợp", "7"),
    Pair("Bàn tay vàng", "44"),
    Pair("Báo thù rửa hận", "46"),
    Pair("BE", "119"),
    Pair("BP", "151"),
    Pair("Cán bộ cao cấp", "98"),
    Pair("Cận đại", "16"),
    Pair("Cao H", "74"),
    Pair("Cẩu huyết", "57"),
    Pair("Chữa lành - cứu rỗi", "162"),
    Pair("Chuyển thể", "154"),
    Pair("Có baby", "136"),
    Pair("Cổ đại", "15"),
    Pair("Cổ xuyên kim", "155"),
    Pair("Con cưng của trời", "41"),
    Pair("Cưng chiều", "109"),
    Pair("Cung đấu", "65"),
    Pair("Cung đình hầu tước", "61"),
    Pair("Cưới trước yêu sau", "42"),
    Pair("Cưỡng ép - chiếm đoạt", "40"),
    Pair("Dân quốc", "17"),
    Pair("DC", "150"),
    Pair("Dị giới", "85"),
    Pair("Dị năng", "128"),
    Pair("Dirty talk", "173"),
    Pair("Dưỡng thê - Nuôi vợ từ bé", "60"),
    Pair("Duyên trời tác hợp", "43"),
    Pair("Đại thúc vs loli - Chênh lệch tuổi tác", "59"),
    Pair("Đam mỹ", "6"),
    Pair("Điên cuồng - độc chiếm - biến thái", "50"),
    Pair("Điền văn", "63"),
    Pair("Đô thị tình duyên", "49"),
    Pair("Đoản văn", "19"),
    Pair("eSport - Thể thao điện tử", "55"),
    Pair("Fake Inc", "71"),
    Pair("Fanfic", "144"),
    Pair("Fragonard", "149"),
    Pair("Gangbang", "175"),
    Pair("GE", "121"),
    Pair("Gia đấu - Trạch đấu", "64"),
    Pair("Giang hồ", "67"),
    Pair("Gương vỡ không lành", "182"),
    Pair("Gương vỡ lại lành", "47"),
    Pair("H văn - Thịt văn", "38"),
    Pair("Hài hước", "113"),
    Pair("Hào môn thế gia", "39"),
    Pair("HE", "117"),
    Pair("Hệ liệt", "24"),
    Pair("Hệ thống", "91"),
    Pair("Hiện đại", "14"),
    Pair("Hóa thú", "132"),
    Pair("Hoán đổi linh hồn", "116"),
    Pair("Huyền Huyễn", "93"),
    Pair("Inc", "70"),
    Pair("Khẩu vị nặng", "157"),
    Pair("Khống", "83"),
    Pair("Không CP", "8"),
    Pair("Không Tam Quan", "161"),
    Pair("Kiếp trước kiếp này", "115"),
    Pair("Kinh dị", "105"),
    Pair("Lạc Miêu Team", "159"),
    Pair("Lâu ngày gặp lại", "177"),
    Pair("Lâu ngày sinh tình", "131"),
    Pair("Lịch sử", "89"),
    Pair("Linh dị thần quái", "124"),
    Pair("Manga", "167"),
    Pair("Mạt thế", "84"),
    Pair("Mất trí nhớ", "185"),
    Pair("Mỹ nhân ngư (Nam/Nữ)", "184"),
    Pair("Mỹ nhân sườn xám", "180"),
    Pair("Mỹ Thực", "53"),
    Pair("Nam cường", "78"),
    Pair("Nam phụ thượng vị - Đập chậu cướp hoa", "174"),
    Pair("Nam trọng sinh", "176"),
    Pair("Nam/nữ có bệnh", "129"),
    Pair("Nam/nữ khiếm khuyết", "133"),
    Pair("Ngây thơ", "77"),
    Pair("Nghiệp giới tinh anh", "143"),
    Pair("Ngôn Tình", "168"),
    Pair("Ngọt", "123"),
    Pair("Ngược", "114"),
    Pair("Ngược luyến tàn tâm", "30"),
    Pair("Ngược luyến tình thâm", "29"),
    Pair("Ngược nam", "32"),
    Pair("Ngược nữ", "33"),
    Pair("Ngược thân", "31"),
    Pair("Ngược tra", "82"),
    Pair("Nham hiểm", "76"),
    Pair("Nhân thú", "90"),
    Pair("Nhất kiến chung tình", "48"),
    Pair("Nhẹ nhàng", "135"),
    Pair("Novel Kr", "187"),
    Pair("NP", "69"),
    Pair("NTR", "165"),
    Pair("Nữ cường", "79"),
    Pair("Nữ giả nam", "141"),
    Pair("Nữ phụ văn", "36"),
    Pair("Nữ tôn", "37"),
    Pair("Nữ truy", "81"),
    Pair("Oan gia", "58"),
    Pair("OE", "120"),
    Pair("On Going", "1"),
    Pair("Phản xuyên", "26"),
    Pair("Quân nhân/Cảnh sát/Đặc công", "97"),
    Pair("Sắc", "110"),
    Pair("Sạch", "68"),
    Pair("SE", "118"),
    Pair("Shoujo", "163"),
    Pair("Showbiz", "102"),
    Pair("SM", "73"),
    Pair("Song trùng sinh", "51"),
    Pair("Song xử", "80"),
    Pair("Song xuyên", "158"),
    Pair("Tâm lý", "104"),
    Pair("Tản văn", "20"),
    Pair("Thâm tình", "111"),
    Pair("Thanh mai trúc mã", "96"),
    Pair("Thanh thuỷ văn", "21"),
    Pair("Thanh xuân vườn trường", "95"),
    Pair("Thanh xuyên", "27"),
    Pair("Thể thao", "56"),
    Pair("Thiên kim thật - giả", "183"),
    Pair("Thực tế", "88"),
    Pair("Thương trường", "100"),
    Pair("Tiên hiệp/ Tu tiên", "94"),
    Pair("Tiểu thuyết Hàn Quốc", "164"),
    Pair("Tình chị em - Hồng hài nhi", "35"),
    Pair("Tinh tế/ABO", "86"),
    Pair("Tình yêu thầy trò", "34"),
    Pair("Tình yêu văn phòng", "45"),
    Pair("Tổng hợp", "169"),
    Pair("TQ", "148"),
    Pair("Tranh quyền đoạt vị", "66"),
    Pair("Trap", "160"),
    Pair("Trinh thám - phá án", "103"),
    Pair("Trùng sinh", "52"),
    Pair("Trước ngược nữ sau ngược nam", "127"),
    Pair("Truyện ⚡", "108"),
    Pair("Truyện Hát - có nội dung", "181"),
    Pair("Truyện ngắn", "23"),
    Pair("Truyện nước ngoài", "146"),
    Pair("Từ thanh xuân đến trưởng thành", "178"),
    Pair("Tự truyện", "137"),
    Pair("Tương lai", "18"),
    Pair("Tuỳ bút", "22"),
    Pair("Tuỳ thân không gian", "92"),
    Pair("Vả mặt", "170"),
    Pair("Viễn tưởng", "87"),
    Pair("Vô hạn lưu", "186"),
    Pair("Võng du", "99"),
    Pair("Võng phối", "139"),
    Pair("Xã hội đen", "101"),
    Pair("Xúc tu", "156"),
    Pair("Xuyên không", "25"),
    Pair("Xuyên nhanh", "28"),
    Pair("Xuyên sách", "112"),
    Pair("Y thuật - Bác sĩ", "54"),
    Pair("Yêu thầm", "130"),
    Pair("️🏏Dụng cụ/Play đủ kiểu", "172"),
    Pair("️🏅Kim bài đề cử", "152"),
    Pair("💣Mìn", "171"),
    Pair("🔞Smut", "106"),
    Pair("📖Xuất bản", "153"),
)

private val STATUS_OPTIONS = listOf(
    Pair("Tất Cả", null),
    Pair("Raw", "1"),
    Pair("Chưa hoàn", "2"),
    Pair("Hoàn thành", "3"),
    Pair("Drop", "4"),
)

private val SORT_BY_OPTIONS = listOf(
    Pair("Tên", "name"),
    Pair("Ngày cập nhật", "updated_at"),
    Pair("Lượt xem", "views"),
    Pair("Số chương", "number_chapter"),
)

private val SORT_ORDER_OPTIONS = listOf(
    Pair("Tăng dần", "asc"),
    Pair("Giảm dần", "desc"),
)
