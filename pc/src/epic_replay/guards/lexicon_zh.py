"""Mandarin banned lists (FR-008, FR-018, FR-046). Each term with its English meaning, because
the owner reviews them in English. The English lists in `lexicon.py` are not touched.

Terms are matched as substrings, after the allowed phrases are masked out: 多少年 holds 少年 and
空中超过 holds 中超, and neither means that. Single characters are too broad (女, 小, 老, 年);
the one exception is 她. The check for a name written in Chinese is only the list below: an
arbitrary name cannot be told from other words (spec.md section 10).
"""

from __future__ import annotations

import re

AGE = {
    "孩子": "child", "小孩": "kid", "儿童": "children", "少儿": "children", "幼童": "small child",
    "小朋友": "little one", "男孩": "boy", "少年": "young teen; adolescent boy", "青少年": "teenager", "青年": "young adult",
    "年轻": "young", "年幼": "very young", "婴儿": "baby", "宝宝": "baby", "幼儿": "toddler",
    "未成年": "minor", "小伙": "young fellow; young man", "小学生": "primary-school pupil", "中学生": "secondary pupil (middle/high schooler)",
    "高中生": "high-school student", "老人": "old person", "老年": "elderly", "年长": "older",
    "高龄": "advanced age", "老头": "old man", "老汉": "old man", "老者": "elder", "长者": "elder",
    "老将": "veteran", "中年": "middle-aged", "大爷": "old fellow; (also respectful) uncle, sir", "大叔": "middle-aged man; uncle-ish older man",
    "爷爷": "grandfather", "外公": "grandfather", "姥爷": "grandfather", "年纪": "age (colloquial)",
    "年龄": "age", "几岁": "how old", "老当益壮": "vigorous for his age", "成年": "adult",
    "成人": "adult",
}  # fmt: skip
FEMALE = {
    "女人": "woman", "女孩": "girl", "女士": "lady", "女子": "woman", "女性": "female",
    "女生": "girl", "女儿": "daughter", "女友": "girlfriend", "女朋友": "girlfriend",
    "女将": "female warrior/general; (fig.) formidable woman", "女运动员": "female athlete", "女选手": "female competitor",
    "女球员": "female player", "女裁判": "female referee", "女教练": "female coach",
    "女司机": "female driver; (derogatory meme) \"woman driver\"", "女同学": "female classmate", "女足": "women's football",
    "女篮": "women's basketball", "女排": "women's volleyball", "女队": "women's team",
    "少女": "young girl", "美女": "beautiful woman",
    "姑娘": "young woman", "闺女": "daughter", "妈妈": "mum", "母亲": "mother", "大妈": "older woman; (often jokey/pejorative) auntie",
    "大婶": "auntie (older woman, respectful address)", "阿姨": "auntie", "奶奶": "grandmother", "外婆": "grandmother",
    "姥姥": "grandmother", "婆婆": "mother-in-law (husband's mother)", "妇女": "woman", "妻子": "wife", "老婆": "wife",
    "太太": "wife, Mrs", "夫人": "Madam, wife", "媳妇": "wife; (regional) daughter-in-law", "新娘": "bride", "小姐": "Miss; young woman (note: can also mean prostitute in some contexts)",
    "姐姐": "older sister", "妹妹": "younger sister",
}  # fmt: skip
FEMALE_PRONOUN = "她"  # she; 她们 (they, female) holds it
APPEARANCE = {
    "胖子": "fat person; chubby person (insulting: fatty)", "肥胖": "obese", "很胖": "very fat", "胖乎乎": "chubby", "小胖": "chubby one",
    "大块头": "big burly guy", "瘦子": "skinny person", "瘦弱": "frail", "消瘦": "gaunt",
    "苗条": "slim", "矮子": "short person", "个子": "height; stature", "身材": "figure",
    "肌肉": "muscle", "丑陋": "ugly", "难看": "ugly; unsightly; embarrassing", "美丽": "beautiful (people, scenery, abstract things)",
    "英俊": "handsome", "帅气": "handsome", "帅哥": "handsome guy", "颜值": "looks; attractiveness",
    "长相": "looks", "性感": "sexy", "秃头": "bald; bald head", "光头": "shaved head; bald head",
}  # fmt: skip
ABILITY = {
    "残疾": "disabled", "残废": "crippled (dated, offensive)", "瘸子": "lame person (derogatory)", "瘫痪": "paralysed",
    "弱智": "retarded (insult; clinical term now 智力障碍)", "智障": "intellectually disabled (in practice almost always an insult: \"retard\")", "傻子": "idiot", "白痴": "idiot; imbecile",
    "脑残": "brain-dead (insult)", "聋子": "deaf person (derogatory)", "瞎子": "blind person (derogatory)", "哑巴": "mute person (derogatory)",
}  # fmt: skip
PROFANITY = {
    "他妈的": "fucking", "妈的": "damn", "操你": "fuck you", "傻逼": "dumbass; fucker", "牛逼": "fucking great; badass; awesome",
    "卧槽": "holy shit", "我靠": "holy shit", "草泥马": "fuck your mother (euphemistic internet pun: grass-mud horse)", "尼玛": "(euphemism) damn; your mother",
    "混蛋": "bastard", "王八蛋": "bastard", "狗屎": "dogshit", "婊子": "bitch", "贱人": "bitch",
    "黑鬼": "racial slur (anti-Black)", "支那": "racial slur (anti-Chinese, historical)", "鬼子": "racial slur (anti-Japanese: \"Japanese devil\")", "阿三": "racial slur (anti-Indian)",
}  # fmt: skip
REAL_PEOPLE = {
    "黄健翔": "commentator", "宋世雄": "commentator", "詹俊": "commentator", "韩乔生": "commentator",
    "刘建宏": "commentator", "贺炜": "commentator", "段暄": "commentator", "张路": "commentator",
    "孙正平": "commentator", "于嘉": "commentator", "杨健": "commentator", "苏群": "commentator (basketball)",
    "赵忠祥": "narrator", "白岩松": "news presenter", "康辉": "news presenter",
    "罗京": "news presenter", "海霞": "news presenter", "李瑞英": "news presenter",
    "崔永元": "presenter", "爱登堡": "Attenborough", "艾登堡": "Attenborough",
    "阿滕伯勒": "Attenborough", "摩根·弗里曼": "Morgan Freeman", "弗里曼": "Freeman",
    "灵魂附体": "catchphrase of a real commentator", "伟大的左后卫": "catchphrase of a real commentator",
}  # fmt: skip
OUTLETS = {
    "央视": "CCTV", "中央电视台": "CCTV", "新闻联播": "CCTV news programme", "朝闻天下": "CCTV news programme",
    "焦点访谈": "CCTV programme", "动物世界": "wildlife programme", "人与自然": "wildlife programme",
    "地球脉动": "Planet Earth", "新华社": "Xinhua", "人民日报": "People's Daily",
    "环球时报": "Global Times", "凤凰卫视": "Phoenix TV", "五星体育": "sports channel",
    "腾讯体育": "Tencent Sports", "英超": "Premier League", "欧冠": "Champions League",
    "中超": "Chinese Super League", "西甲": "La Liga", "德甲": "Bundesliga", "意甲": "Serie A",
    "法甲": "Ligue 1", "亚冠": "AFC Champions League", "世界杯": "World Cup", "欧洲杯": "Euros",
    "奥运会": "Olympics", "美职篮": "NBA", "国际足联": "FIFA", "欧足联": "UEFA", "足协": "football association",
}  # fmt: skip
NEWSROOM = {
    "突发新闻": "breaking news", "现场直播": "live broadcast", "本台记者": "this station's reporter",
    "本台消息": "this station reports", "新闻直播间": "news studio",
}  # fmt: skip
# Not banned as such: a name or place is an invented name unless the user gave it (rule 4).
FAMOUS_NAMES = {
    "梅西": "Messi", "罗纳尔多": "Ronaldo (Brazilian, R9)", "C罗": "Ronaldo (Cristiano)", "内马尔": "Neymar", "姆巴佩": "Mbappé",
    "哈兰德": "Haaland", "贝克汉姆": "Beckham", "齐达内": "Zidane", "马拉多纳": "Maradona",
    "贝利": "Pelé", "穆里尼奥": "Mourinho", "瓜迪奥拉": "Guardiola", "孙兴慜": "Son Heung-min",
    "武磊": "Wu Lei", "科比": "Kobe", "乔丹": "Jordan", "詹姆斯": "James", "库里": "Curry",
    "姚明": "Yao Ming", "易建联": "Yi Jianlian", "刘翔": "Liu Xiang", "苏炳添": "Su Bingtian",
    "林丹": "Lin Dan", "伦敦": "London", "巴黎": "Paris", "马德里": "Madrid", "巴塞罗那": "Barcelona",
    "曼彻斯特": "Manchester", "利物浦": "Liverpool", "米兰": "Milan", "慕尼黑": "Munich",
    "北京": "Beijing", "上海": "Shanghai", "广州": "Guangzhou", "巴西": "Brazil", "阿根廷": "Argentina",
    "英格兰": "England", "西班牙": "Spain", "法国": "France", "德国": "Germany", "意大利": "Italy",
    "葡萄牙": "Portugal", "皇马": "Real Madrid", "巴萨": "Barcelona FC", "曼联": "Man United",
    "曼城": "Man City", "阿森纳": "Arsenal", "切尔西": "Chelsea", "拜仁": "Bayern", "尤文": "Juventus",
    "温布利": "Wembley",
}  # fmt: skip

# Masked before the lists are read: ordinary phrases that hold a banned term.
ALLOWED = {
    "多少年": "how many years", "中超过": "overtook in", "中超越": "overtook in", "中超出": "exceeded in",
    "不难看出": "it is easy to see", "难看出": "hard to see", "今年长": "grew this year",
    "太太太": "so, so (太太太棒了)", "紧张路": "tense road", "我靠近": "I come near", "我靠着": "I lean on",
    "仓库里": "in the warehouse", "车库里": "in the garage", "水库里": "in the reservoir",
    "肌肉记忆": "muscle memory", "小伙伴": "pal", "中年度": "in the year's", "中超人": "Superman in",
    "防线瘫痪": "the defence collapsed", "完成人": "completed by; finishing a person's",
    "变成人": "turned into a person", "达成人": "reaching a person's",
}  # fmt: skip
# 太太 is "wife" unless an adjective follows: 太太棒了 is "so, so great".
ALLOWED_WHEN = {r"太太(?=棒|好|厉害|精彩|漂亮|强|快)": "太太 before an adjective: so, so"}
# Masked before numerals are read: ordinary words that hold a numeral, and "a few" phrases.
NOT_NUMBERS = {
    "十足": "fully", "十拿九稳": "a sure thing",
    "千钧一发": "at the critical moment", "千载难逢": "once in a lifetime", "万一": "in case",
    "万分": "extremely", "万众瞩目": "all eyes on it", "万无一失": "without fail",
    "百分百": "one hundred percent", "百发百中": "never misses", "再三": "again and again",
    "三番五次": "time after time", "四平八稳": "steady; well-balanced", "四面八方": "from all sides",
    "七上八下": "nervous", "五花八门": "all kinds of", "九死一生": "a narrow escape",
    "两全其美": "best of both worlds", "二话不说": "without a word", "三心二意": "half-hearted",
    "一两": "one or two", "两三": "two or three", "三五": "a few", "十几": "a dozen or so",
    "几十": "dozens", "几百": "hundreds", "上百": "hundreds", "上千": "thousands",
    "四周": "all around", "四溢": "spreading everywhere", "四处": "everywhere", "四散": "scattering",
    "四射": "radiating", "四肢": "limbs", "十字": "cross", "三角": "triangle", "八成": "most likely",
    "九成": "most of it", "十成": "completely", "百般": "in every way", "千里": "far away",
    "万里": "far away", "万能": "all-purpose", "万岁": "long live", "七零八落": "scattered",
    "五颜六色": "colourful", "两边": "both sides", "两侧": "both sides", "两旁": "both sides",
    "两手": "both hands", "两脚": "both feet", "零距离": "up close", "零星": "scattered",
    "四两拨千斤": "a light touch beats great force", "百年一遇": "once in a century",
    "十全十美": "perfect", "百分之百": "one hundred percent", "千变万化": "ever-changing",
    "三下五除二": "in no time", "一举两得": "kill two birds with one stone; two gains at one go",
}  # fmt: skip
# Not a number here, but a number beside other words: 十分钟 is ten minutes, 一千万 ten million.
NOT_NUMBERS_WHEN = {
    r"十分(?!钟)": "十分 not before 钟: very",
    r"(?<![一二两三四五六七八九十百])千万": "千万 not after a numeral: must, by all means",
}
NUMERAL_RUN = re.compile(
    r"(?<![第零〇一二两三四五六七八九十百千万])[零〇一二两三四五六七八九十百千万]+"
)
# Traditional forms that GB 2312 also holds, so its check cannot see them. 著 and 乾 are not here:
# both are ordinary Simplified characters too (著名 famous, 乾坤 heaven and earth).
TRADITIONAL_IN_GB2312 = set("後於徵麽")
DIGIT = dict(zip("零〇一二两三四五六七八九", (0, 0, 1, 2, 2, 3, 4, 5, 6, 7, 8, 9), strict=True))
UNIT = {"十": 10, "百": 100, "千": 1000}

BANNED = {
    **AGE, **FEMALE, **APPEARANCE, **ABILITY, **PROFANITY, **REAL_PEOPLE, **OUTLETS, **NEWSROOM,
}  # fmt: skip


def _masked(text: str, phrases, patterns=()) -> str:
    for p in sorted(phrases, key=len, reverse=True):
        text = text.replace(p, "□" * len(p))
    for p in patterns:
        text = re.sub(p, lambda m: "□" * len(m.group(0)), text)
    return text


def find_banned(text: str, extra: list[str] | tuple[str, ...] = ()) -> list[str]:
    """Banned terms in `text`: the lists, 她, and a pack's own terms in Chinese."""
    masked = _masked(text, ALLOWED, ALLOWED_WHEN)
    found = [t for t in (*BANNED, *extra) if t in masked]
    return found + ([FEMALE_PRONOUN] if FEMALE_PRONOUN in masked else [])


def famous_names(text: str) -> list[str]:
    masked = _masked(text, ALLOWED, ALLOWED_WHEN)
    return [n for n in FAMOUS_NAMES if n in masked]


def _value(run: str) -> int:
    if not set(run) - set(DIGIT):  # read figure by figure: 二〇二六
        return int("".join(str(DIGIT[c]) for c in run))
    total, section, cur = 0, 0, 0
    for c in run:
        if c in DIGIT:
            cur = DIGIT[c]
        elif c == "万":
            total = (total + section + max(cur, 0 if section else 1)) * 10000
            section, cur = 0, 0
        else:
            section += max(cur, 1) * UNIT[c]
            cur = 0
    return total + section + cur


def numbers_in(text: str) -> set[str]:
    """The Chinese numerals in `text` as digit strings. 一 alone, 半 and ordinals are not counted,
    as English does not count "one"."""
    masked = _masked(text, NOT_NUMBERS, NOT_NUMBERS_WHEN)
    return {str(_value(r)) for r in NUMERAL_RUN.findall(masked) if r != "一"}
