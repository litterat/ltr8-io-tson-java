# tson-encoding — the binding both encodings read into Java objects through

`TsonSchemaBinding`: which host class each entry of a linked schema binds to under one `DataBindContext`. Both
stacks' bind readers are built from it. Read `design/modules.md` for the boundary.

- A factory in either stack asks the binding; it never calls `DataBindContext.getDescriptor` for a schema entry or
  walks an alias itself. Which entry a name is asked as is `TsonLinkedSchema.bindingCandidates`, in `tson-schema`.
- Every answer is stored at `load`; a query never consults the context. Nothing here may describe a class lazily.
- Answers are values (`Descriptor`); the compile turns them into its exceptions. No answer is `NOT_IMPLEMENTED`.
- Names no type of either stack.
