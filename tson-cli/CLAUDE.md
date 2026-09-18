# tson-cli — the `tson` command

Read `design/cli-config-hashing.md`.

- Exit codes are a contract: 0 valid, 1 a verdict on a document (a §9.1 limit refusal included), 2 usage, 69/75/78 a
  schema nothing would supply or a misconfiguration, 70 a library gap or fault. `TsonCli.exitCodeFor` ranks
  `70 > 78 > 69 > 75 > 1`; the split rides on `Diagnostic.Code`, not on which channel a problem arrived by.
- Schemas are classified by embedded `!!id`, never by filename; `.json` is the one extension read, and its
  `--schema`/`--type` binding errors are usage errors.
- The report goes to stdout unchanged; non-verdict notes go to stderr. Machine formats always carry the `policy` field.
- The CLI's own schemas are `https://tson.io/2026/36/ltr8/cli/<name>-<version>.tn`, versioned per release.
