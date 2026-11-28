# tson-encoding — the binding both encodings read into Java objects through

`TsonSchemaBinding`: one `TsonTypeBinding` per entry of a linked schema the binder binds under one
`DataBindContext`, found by `EntryKey`, written name or class; an entry nothing binds is absent. Both stacks' bind
readers are built from it. Read `design/modules.md` for the boundary.

- A factory in either stack asks the bindings; it never calls `DataBindContext.getDescriptor` for a schema entry or
  walks an alias itself. Which entry a name is asked as is `TsonLinkedSchema.bindingCandidates`, in `tson-schema`.
- Every answer is stored at load; a query never consults the context. Nothing here may describe a class lazily, and
  an absent entry keeps only its reason string, never an exception.
- `TsonSchemaBinding.load` is strict: a class the context cannot describe fails it (`BindMismatchException`). The
  lenient load is a resolution's checkpoint's (`plan.BindingTable.checkpoint`), in the package exported only to the
  two stacks; a consumer never sees one, and a compiled schema built over one hands out no `binding()`.
- A parametric record is never asked of the binder (§5.10).
- Several entries binding one class is legitimate; `get(Class)` refuses the question when asked, never the load.
- Names no type of either stack.
