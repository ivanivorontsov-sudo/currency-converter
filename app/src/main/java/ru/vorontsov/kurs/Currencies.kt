package ru.vorontsov.kurs

data class Cur(val code: String, val name: String, val flag: String, val crypto: Boolean = false)

private fun f(cc: String) = cc.uppercase().map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("")

val ALL_CURRENCIES: List<Cur> = listOf(
    Cur("RUB","Российский рубль",f("RU")), Cur("USD","Доллар США",f("US")), Cur("EUR","Евро",f("EU")),
    Cur("BYN","Белорусский рубль",f("BY")), Cur("CNY","Китайский юань",f("CN")), Cur("KZT","Казахстанский тенге",f("KZ")),
    Cur("UAH","Украинская гривна",f("UA")), Cur("GBP","Фунт стерлингов",f("GB")), Cur("JPY","Японская иена",f("JP")),
    Cur("CHF","Швейцарский франк",f("CH")), Cur("TRY","Турецкая лира",f("TR")), Cur("AED","Дирхам ОАЭ",f("AE")),
    Cur("THB","Тайский бат",f("TH")), Cur("KRW","Южнокорейская вона",f("KR")), Cur("GEL","Грузинский лари",f("GE")),
    Cur("AMD","Армянский драм",f("AM")), Cur("AZN","Азербайджанский манат",f("AZ")), Cur("UZS","Узбекский сум",f("UZ")),
    Cur("KGS","Киргизский сом",f("KG")), Cur("TJS","Таджикский сомони",f("TJ")), Cur("TMT","Туркменский манат",f("TM")),
    Cur("MDL","Молдавский лей",f("MD")), Cur("PLN","Польский злотый",f("PL")), Cur("CZK","Чешская крона",f("CZ")),
    Cur("HUF","Венгерский форинт",f("HU")), Cur("RON","Румынский лей",f("RO")), Cur("BGN","Болгарский лев",f("BG")),
    Cur("RSD","Сербский динар",f("RS")), Cur("SEK","Шведская крона",f("SE")), Cur("NOK","Норвежская крона",f("NO")),
    Cur("DKK","Датская крона",f("DK")), Cur("ISK","Исландская крона",f("IS")), Cur("CAD","Канадский доллар",f("CA")),
    Cur("AUD","Австралийский доллар",f("AU")), Cur("NZD","Новозеландский доллар",f("NZ")), Cur("HKD","Гонконгский доллар",f("HK")),
    Cur("SGD","Сингапурский доллар",f("SG")), Cur("INR","Индийская рупия",f("IN")), Cur("IDR","Индонезийская рупия",f("ID")),
    Cur("VND","Вьетнамский донг",f("VN")), Cur("MYR","Малайзийский ринггит",f("MY")), Cur("PHP","Филиппинское песо",f("PH")),
    Cur("LKR","Шри-ланкийская рупия",f("LK")), Cur("MNT","Монгольский тугрик",f("MN")), Cur("ILS","Израильский шекель",f("IL")),
    Cur("EGP","Египетский фунт",f("EG")), Cur("SAR","Саудовский риял",f("SA")), Cur("QAR","Катарский риал",f("QA")),
    Cur("IRR","Иранский риал",f("IR")), Cur("ZAR","Южноафриканский рэнд",f("ZA")), Cur("BRL","Бразильский реал",f("BR")),
    Cur("MXN","Мексиканское песо",f("MX")), Cur("ARS","Аргентинское песо",f("AR")), Cur("CUP","Кубинское песо",f("CU")),
    Cur("RUB_","",""),
    Cur("XAU","Золото (тройская унция)","🥇"), Cur("XAG","Серебро (тройская унция)","🥈"),
    Cur("BTC","Биткоин","₿",true), Cur("ETH","Эфир","Ξ",true), Cur("USDT","Tether","₮",true), Cur("TON","Toncoin","💎",true),
    Cur("SOL","Solana","◎",true), Cur("LTC","Litecoin","Ł",true), Cur("DOGE","Dogecoin","Ð",true),
).filter { it.code != "RUB_" }

val CUR_MAP = ALL_CURRENCIES.associateBy { it.code }
fun cur(code: String) = CUR_MAP[code] ?: Cur(code, code, "🏳")
