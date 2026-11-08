# tson-cli — the `tson` command

Read `design/cli-config-hashing.md`.

- Exit codes are a contract: 0 accepted, 1 rejected (invalid, or a §8.2/§9.1 refusal), 2 usage, 69/75/78 a
  schema nothing would supply or a misconfiguration, 70 a library gap or fault. `TsonCli.exitCodeFor` ranks
  `70 > 78 > 69 > 1 > 75`; the split rides on `Diagnostic.Code`, not on which channel a problem arrived by.
- A file is a schema when its root is tagged `!schema`, and a schema is matched by its embedded `!!id`, never by
  filename; `.json` is the one extension read, and its
  `--schema`/`--type` binding errors are usage errors.
- The report goes to stdout unchanged; non-verdict notes go to stderr. Machine formats always carry the `policy` field.
- The CLI's own schemas are `https://tson.io/2026/39/io/ltr8/cli/<name>.tn`; `diagnostics.tn` imports the spec's `core.tn` and `policy.tn`.
