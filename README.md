# kotoba-lang/org-ieee-find — POSIX `find`, as a Kotoba command binary

The `find` walk from IEEE Std 1003.1, with no expression and with `-type f`,
written in `.kotoba` and compiled to a standalone native executable.

```sh
./find DIR           # every path under DIR, DIR included
./find DIR -type f   # the regular files only
```

## The order is not matched, and cannot be

`/usr/bin/find` emits entries in the order the directory hands them over —
**readdir order**, which is neither sorted nor stable across filesystems.
Measured 2026-09-10 on a directory holding `a`, `c` and `f1.txt`, it emitted
`f1.txt` **before** `a`.

Wire 34 answers its listing **sorted by bytes**, and there is no request form
that asks for readdir order. So this walk cannot reproduce that sequence, and
the test compares both outputs **sorted** — the set, not the sequence. Same
shape as [`org-ieee-ls`](https://github.com/kotoba-lang/org-ieee-ls) comparing
against `LC_ALL=C` and
[`org-ieee-grep`](https://github.com/kotoba-lang/org-ieee-grep) against `-F`:
name the difference rather than paper over it.

On the test fixture the two sequences happen to agree exactly. That is a
coincidence of how the files were created, not a property, and it is not
asserted.

What this *does* guarantee is its own order: **depth-first, each directory
before its contents, files before subdirectories, every listing in byte
order.** That is deterministic, which readdir order is not.

## A control that passes, on purpose

Changing the traversal from depth-first to breadth-first **does not fail the
suite** — the set is the same. That is not a gap in the test; it is the test
being exactly as strong as its claim. A suite that failed there would be
asserting an order the README says is not matched.

The two controls that *do* fail are the ones about content: reading `-type f`
as two arguments instead of three leaves it doing nothing (and only that case
shows it), and skipping the directory entry itself loses eight paths.

## The stack is a string

Mutual recursion is not available — a callee must be declared before its
caller — so "visit this directory, then recurse into each subdirectory"
cannot be written as two functions calling each other. The directories still
to visit are carried as one `"\n"`-joined stack, pushed at the **front** so
the walk is depth-first, and every function here is self-recursive.

## Capabilities and size

`:cli/args` (38), `:fs/browse` (34), `:io/write` (37), `:io/write-error` (39).

A tree walk spends about eight pair handles per entry and concatenates a path
per entry, and neither arena reclaims — so package with `--pairs` and
`--string-pool` that match the tree. The suite uses 200,000 and 8 MB for
fifteen paths, which is far more than it needs and far less than `orgs/`
would.

## What this is not

No expression language: no `-name`, `-path`, `-newer`, `-maxdepth`, `-exec`,
`-print0`. `-type` accepts only `f`. One operand, and it must be an absolute
path inside the packaged browse scope.
