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

## Several roots, `-type d`, and the roots that are not directories

```
find DIR              every path under DIR, DIR included
find DIR -type f      the regular files
find DIR -type d      the directories
find FILE             FILE itself, not descended into
find A C              several roots, walked in turn
find nope             find: nope: No such file or directory      exit 1
```

The suite went from **2 cases to 14**, and the three it gained were not
polish — each covered a defect:

- **`-type` was a BOOLEAN**, so anything that was not `-type f` fell through
  to "report everything" and `find DIR -type d` listed the files too. A
  two-valued answer to a three-valued question is a wrong answer, not a
  missing feature. Restoring the boolean fails the 2 `-type d` cases.
- **A missing root TRAPPED.** The only guard compared `listing` against a
  sentinel string that could never match, so a root that was not there
  reached the browse wire and died with SIGILL. Removing the check now fails
  the 3 cases involving one.
- **A root that is a file reported nothing.** Making it silent again fails
  the 2 file-root cases.

## `dir?` reads STAT, not the parent's listing

[`org-ieee-cp`](https://github.com/kotoba-lang/org-ieee-cp) and
[`org-ieee-ls`](https://github.com/kotoba-lang/org-ieee-ls) answer "is this a
directory" by browsing the path's **parent**. That cannot work here: find's
root is frequently the granted scope root itself, whose parent lies **outside
the grant**, so browsing it traps.

That is exactly how it was found — the three cases whose root was the scope
root died with SIGILL while every case with a root beneath it passed. `STAT`'s
fourth field answers is-directory for the path itself, with no parent
involved.

## Order is still not compared

`/usr/bin/find` emits in readdir order and wire 34 answers sorted, so stdout
is compared as a **sorted set**. stderr and exit status are compared byte for
byte — and for a missing root, stderr is the *only* thing that separates it
from an empty result, since both put nothing on stdout.

## What this is not

No expression language: no `-name`, `-path`, `-newer`, `-maxdepth`, `-exec`,
`-print0`. `-type` accepts `f` and `d` only — `-type l` would need symlink
detection, and wire 35 opens `O_NOFOLLOW`. The flag must come last, and with
no operand at all this exits 1 rather than walking a working directory it
cannot ask for.
