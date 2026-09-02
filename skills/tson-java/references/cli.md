# The `tson` command line

A zero-dependency CLI (ajv-cli-style) for checking TSON from a shell, a Makefile or a CI job — whatever
language the surrounding project is written in. Nothing here needs Java to be written.

```bash
./gradlew :tson-cli:installDist
export PATH="$PWD/tson-cli/build/install/tson/bin:$PATH"
```

Or without installing: `./gradlew :tson-cli:run --args="compile schema.tn"`.

## Commands

```
tson init-example [<dir>]                            write an example schema + data file to try
tson validate [<options>] <file|->...                validate data documents
tson compile [<options>] <schema>                    check that a schema resolves and compiles
tson policy [<options>]                              print the Unicode policy this run would apply
tson hash <file>                                     stamp a content hash onto a document's !!id
```

**Help is two levels.** `tson --help` lists the commands; `tson <command> --help` gives that command's own
options, exit codes and description — including the policy options for the three commands that judge a name.

|                       |                                                                                                     |
| --------------------- | --------------------------------------------------------------------------------------------------- |
| Arguments             | **a flat file list** — each auto-classified as schema (its header carries `!!meta`) or data, by content, never by filename |
| Schema selection      | entirely the data's own: its `!!schema` names the schema, its root type-ref (`!person`) the type. There is no `--type`, and no `--schema` |
| `-`                   | reads one data document from stdin, at most once, always data (a file really named `-` is `./-`)     |
| `--output`            | `text` (default), `json`, `tson`                                                                    |
| Exit codes            | `0` checked, nothing reported · `1` checked and rejected · `2` usage · `69` a schema nothing would supply · `75` a schema that could not be reached · `78` a type with no Java class here · `70` library gap or fault |

**The CLI fetches nothing** — schemas come from the files you list, and one it cannot match is
`SCHEMA_NOT_FOUND` and exit 69, not a verdict on your data. Everything above `2` is deliberately kept apart
from `1`: `1` is a verdict on the document, the rest are the *absence* of one, naming who could not give it —
this library (`70`), an application that would have to bind the type (`78`), whoever was to serve the schema
(`69` permanently, `75` perhaps not). `TsonCli.exitCodeFor` lifts a mixed run to one code, ranked by who must
act before anyone else's fix counts and with permanence breaking the tie — `70` > `78` > `69` > `75` > `1`.
A §8.2 name-hygiene refusal is a `1`: the processor looked and declined, and the sender holds the fix.
`validate` collects every problem in a file in one pass.

```bash
tson init-example .                                   # writes person.tn + person-data.tn
tson validate person.tn person-data.tn                # OK
tson validate --output json person.tn bad-data.tn
printf '!person { name: "Ada" }' | tson validate person.tn -
tson compile person.tn                                # does the schema itself resolve and compile?
tson hash person.tn                                   # stamp ?sha256=… onto its own !!id, in place
```

## The Unicode policy, and configuring it

[TSON-DATA] §8.2's name-hygiene rules read Unicode data the Consortium declines to freeze, at a level *this
deployment* chooses — so the same document can be refused here and accepted elsewhere. That reason is in
neither your document nor your schema, which is why every report states it and why `tson policy` prints it
with no document in hand:

```
$ tson policy
identifier policy: HIGHLY_RESTRICTIVE
token policy:      UNRESTRICTED
unicode data:      16.0
```

**Read it before you generate and the refusal never happens** — that is the useful direction, and the reason
the command exists. A generator that learns the policy from a rejection has already spent a round trip.

Three commands take the policy options — `validate`, `compile`, `policy`:

```
--identifier-policy <level>    level for identifiers (default: highly-restrictive)
--identifier-per-segment       apply it per _/- segment rather than the whole identifier
--identifier-scripts <A+B>     admit one script combination over the level (repeatable)
--token-policy <level>         level for values (default: unrestricted, which scans nothing)
--token-scripts <A+B>          the same for values (repeatable)
```

`<level>` is one of UTS #39 §5.2's six — `ascii-only`, `single-script`, `highly-restrictive`,
`moderately-restrictive`, `minimally-restrictive`, `unrestricted` — and the spelling `tson policy` prints
(`HIGHLY_RESTRICTIVE`) is accepted too, so its output is usable as its input. `tson policy` with the same
flags is a dry run of what a `validate` under them would apply.

```bash
# names.tn declares `id_адрес => text`
tson compile names.tn                                     # refused: the name mixes Latin and Cyrillic
tson compile --identifier-per-segment names.tn            # OK: each _-delimited segment is one script
tson compile --identifier-scripts Latin+Cyrillic names.tn # OK: the combination is named
```

Reach for the **unit** or a **named combination** before dropping a level — both keep the rule everywhere
else. Four things are worth knowing before you configure one:

- **§8.2 requires a relaxation not be silent**, which is a rule about *ambient authority*: a flag in a CI
  file satisfies it where an environment variable would not. Accordingly `--output text` prints the policy on
  any run that **configured** one, not only on a run that refused something.
- **`--token-scripts` alone raises the token level** from `unrestricted` to `single-script`. A list of
  admitted combinations is no configuration at all under a level that scans nothing.
- **A relaxation named against a level that scans nothing is a usage error**, not a silent no-op —
  `--token-policy unrestricted --token-scripts Latin+Cyrillic` configures nothing whatever.
- **There is no `--token-per-segment`.** `_` and `-` are ordinary characters in a value rather than word
  separators, and UTS #39's own `Toys-Я-Us` is the spoof segmenting one would wrongly admit; the library
  refuses such a policy outright.

The flags build one `Tson` per run, so a schema's own declared names and your data's names are judged under
one setting.

## Machine-readable output

`--output json` is one document per invocation, one file or twenty — a `files` array with each data file's
own `file`/`outcome`/`errors`, wrapped in the run's own outcome. **Both machine formats spell one report one
way: `snake_case` keys, and a field with nothing to say left out rather than written `null`.**

```json
{"outcome":"INVALID",
 "policy":{"identifier_policy":{"level":"HIGHLY_RESTRICTIVE","per_segment":false,"permitting":[]},
           "token_policy":{"level":"UNRESTRICTED","per_segment":false,"permitting":[]},
           "unicode_data_version":"16.0"},
 "files":[{"file":"person-data.tn","outcome":"INVALID","errors":[
   {"path":"/age","schema_pointer":"/person/age","schema_id":"example.com/…/person.tn",
    "code":"ATOM_CONSTRAINT_VIOLATION","message":"'int32': 'thirty' is not a valid integer …",
    "expected":"an integer or based-integer form","actual":"thirty",
    "data_position":"5:8:154","schema_position":"17:5:677"}]}],
 "errors":[]}
```

**`outcome` is `VALID` / `INVALID` / `NOT_CHECKED`, not a `valid` boolean**, because those are two questions
and one bit cannot carry both: a document whose schema was never obtained, or whose types have no Java class
in this tool, was never read, and calling it `valid: false` asserts a verdict the run cannot make — the
assertion an agent acts on the moment it writes `if (!valid)`. `NOT_CHECKED` is exactly the set of codes that
are not a verdict: the five `SCHEMA_*` fetch codes, `BIND_MISMATCH` and `NOT_IMPLEMENTED`. One of them in a
file makes that file `NOT_CHECKED`, and one such file makes the run `NOT_CHECKED` — a run being no better
than its parts, and its exit code then one of `69`, `75`, `78`, `70`. A run that never reached a document at
all (a usage or classification failure, exit `2`) is `NOT_CHECKED` with an empty `files` too.

`policy` sits between `outcome` and `files` on every envelope — the §8.2 configuration the run was judged
under, stated once because it is constant for the run and cannot differ between two of its problems. A
§8.2 refusal is an ordinary diagnostic told apart by its `code` (`CONFUSABLE_NAMES`, `RESTRICTED_CHARACTER`,
`RESTRICTED_SCRIPT`), carries nothing extra, and leaves the file `INVALID` rather than `NOT_CHECKED`.

`--output tson` is the same record through the library's own writer — the shape `tson-cli`'s own
`diagnostics.tn` declares, which that output is validated against, and which `--output json` now matches key
for key. A position is `line:column:byteOffset`,
the first two 1-based, the offset counting UTF-8 bytes from 0. The top-level `errors` carries only what
stopped the run before any document was read.

## Pitfalls

| You wrote                                                    | Problem                                                                | Do this instead                                       |
| ------------------------------------------------------------ | ---------------------------------------------------------------------- | ------------------------------------------------------ |
| a `null` expected where a field is absent                    | both formats omit an absent field rather than writing `null`           | test the key's presence, not its value                |
| `--type` or `--schema`                                       | neither exists; selection is the document's own                        | put `!!schema` and a root type-ref in the data        |
| a schema passed by filename convention                       | classification is by content (`!!meta`), and matching is by `!!id`      | check the `!!id` the schema declares                  |
| exit `70` read as "invalid document"                         | it is a gap or a fault in this library, not a verdict                  | treat it as a bug report                              |
| exit `69` read as "invalid document"                         | no schema was obtained, so nothing was judged                          | pass the schema file, or check the `!!id`             |
| exit `75` retried forever, or `69` retried at all            | `75` says a rerun may help; `69` says it will not                      | retry `75`; fix the reference or allow-list for `69`  |
| `if (!valid)` against the envelope                           | there is no `valid`; `NOT_CHECKED` is not `INVALID`                    | switch on `outcome` — three states, no falsy shortcut |
| a refusal treated as a permanent verdict                     | another deployment's policy may accept the same name                   | read `policy` in the report, or run `tson policy`     |
| `--token-scripts` with `--token-policy unrestricted`         | a list of combinations under a level that scans nothing                | name a level that scans, or drop the list             |
| relaxing a policy in a shell profile                         | ambient authority is what §8.2's non-silence rule is about             | put the flag in the CI file or Makefile that runs it  |
