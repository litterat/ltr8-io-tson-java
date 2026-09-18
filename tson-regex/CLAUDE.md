# tson-regex — RFC 9485 I-Regexp

A Thompson-NFA/Pike-VM matcher (linear time, no backtracking) plus an exact `isDisjointFrom`. Depends on nothing and
knows nothing of TSON. Never delegate to `java.util.regex` — it is a laxer superset and not ReDoS-safe.
