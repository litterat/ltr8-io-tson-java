# tson — the front door

One class, `Tson`, over `tson-compiler`. Read `design/front-door-and-config.md`.

- `ProcessorConfig` is not here — it is a value in `tson-base`; construction (`Tson.of(config)`) is what lives here.
- `Tson.validateSchema` owns the phase boundary: resolve only if the parse was whole, link only if resolution was clean,
  never register a schema that reported anything.
- Concurrent reads through one `Tson` are safe and stated on the class; `SharedInstanceConcurrencyTest` pins it.
- `Class2ConformanceSuiteTest` and the allocation harness live in this module's tests (`design/conformance-suite.md`,
  `design/build.md`).
