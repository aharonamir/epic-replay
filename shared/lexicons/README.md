# Word lists

The banned and watched words, one file per language. Both apps read these files; neither keeps its own copy (constitution principle XI). `shared/contracts/lexicon.schema.json` validates both. The cases in `shared/conformance/lexicon/` show the expected results; a port passes them.

| File | Language | Read by (PC app) |
|---|---|---|
| `en.json` | English | `epic_replay.guards.lexicon` |
| `zh.json` | Mandarin, Simplified | `epic_replay.guards.lexicon_zh` |

The lists may change only with a spec change: `GLOBAL_BANNED` is part of the English golden hash (`pc/tests/fixtures/english_golden.json`), so a change there changes the golden file too.

## English (`en.json`)

`categories` holds each list in order; `global_banned` names the categories, in order, that make the global list, and `descriptor_banned` the ones that may not describe a person in the play sheet (FR-008).

**Matching** (`find_banned`, `find_descriptor_banned`): whole word, case-insensitive.

1. The terms are the categories' terms in the order `global_banned` gives them. A style pack's own `banned_terms` are matched as a second list, after the global one.
2. Each term is lowercased and escaped; each space in a term matches one or more whitespace characters.
3. The terms are tried longest first, as one alternation.
4. A match must not have an ASCII letter or digit (`A-Z`, `a-z`, `0-9`) directly before or after it. So `bold`, `holds`, `herald` and `kidney` do not match `old`, `her` or `kid`; `she's` matches `she`.
5. The result is the matched text, lowercased, in order of where it occurs; the pack's matches follow the global ones. A term found twice is listed twice.

## Mandarin (`zh.json`)

`categories` holds each banned list with an English gloss per term; `banned` names the banned categories in order. The other keys:

| Key | What it is |
|---|---|
| `female_pronoun` | 她, the one single character that is banned. 她们 holds it. |
| `famous_names` | Names and places that are an invented name unless the user typed them (stage-contracts rule 4). Not banned as such. |
| `allowed` | Ordinary phrases that hold a banned term (多少年 holds 少年). Masked first. |
| `allowed_when` | Patterns masked with `allowed` (太太 before an adjective means "so, so"). |
| `not_numbers` | Ordinary words that hold a numeral (十足, 万一). Masked before numerals are read. |
| `not_numbers_when` | Patterns masked with `not_numbers` (十分 not before 钟). |
| `numeral_run` | The pattern of one run of Chinese numerals. |
| `digits`, `units` | The value of each digit (零 and 〇 are 0, 两 is 2) and of 十, 百, 千. 万 is handled by the algorithm below. |
| `traditional_in_gb2312` | Traditional forms that GB 2312 also encodes, so the Simplified-only check treats them as Traditional. |

**Masking.** Replace each phrase of the list, longest first, with as many `□` (U+25A1) as it has characters; then replace each match of each pattern, in file order, the same way. The masked text has the same length as the original.

**Banned terms** (`find_banned`): mask with `allowed` and `allowed_when`. Then, for each term of the `banned` categories in order (a term in two categories counts once, in its first place), followed by a style pack's own Chinese `banned_terms`, report the term once if it occurs anywhere in the masked text (substring, no word boundaries). A pack term that is also in the lists is reported twice, once for each place. Then report `她` if it occurs.

**Famous names** (`famous_names`): the same masking; each listed name that occurs, in list order.

**Numerals** (`numbers_in`): mask with `not_numbers` and `not_numbers_when`. Find every match of `numeral_run`. Ignore a run that is exactly `一` (as English does not count "one"). Read each run:

- if every character is in `digits`, read it figure by figure: 二〇二六 is 2026;
- otherwise keep `total`, `section` and `cur`, all 0, and for each character: a digit sets `cur`; 万 sets `total = (total + section + max(cur, 0 if section else 1)) × 10000` and clears `section` and `cur`; a unit adds `max(cur, 1) × unit` to `section` and clears `cur`. The value is `total + section + cur`: 十五 is 15, 一千万 is 10000000, 两百零三 is 203.

The result is the set of values as decimal strings.

**Simplified only**: a CJK character outside GB 2312, or in `traditional_in_gb2312`, is not Simplified. Names the user typed are removed first.

## Patterns: the portable subset

Patterns stored in these files must behave the same in Python `re` and in Java/Kotlin `java.util.regex`. Only this syntax is allowed (`pc/tests/unit/test_shared.py` checks every stored pattern):

- literal characters, including CJK; no escapes other than `\` before one of `. * + ? ( ) [ ] { } | \ ^ $ -`
- character classes `[…]` and negated classes `[^…]` of literal characters
- the quantifiers `?`, `*`, `+` and `{m}`, `{m,}`, `{m,n}`
- alternation `|`, and non-capturing groups `(?:…)`
- lookahead `(?=…)` and negative lookahead `(?!…)`
- negative lookbehind over one character class, `(?<![…])`: fixed width, one character (agreed by the owner on 2026-11-14)

Inside a class, `&&` and a `]` as the first character are not allowed (Java reads them differently), and a quantifier may not follow a lookaround.

Not allowed: capturing groups, named groups, back-references, other lookbehind, inline flags such as `(?i)`, anchors, `\d`, `\w`, `\s`, `\b` and other escape classes, Unicode property classes, possessive and lazy quantifiers.
