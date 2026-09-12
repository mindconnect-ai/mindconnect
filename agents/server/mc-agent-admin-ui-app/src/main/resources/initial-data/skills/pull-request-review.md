---
name: pull-request-review
description: Use when reviewing a pull request, a diff or a patch before it is merged
tools: bash, file_read, grep
---
# Reviewing a change

An example skill, and a working one — edit it, or delete it, to make the
screen yours. It shows the shape a skill has: a description that says WHEN,
and instructions long enough to be worth loading.

## Read the change before judging it

1. Get the diff itself (`git diff`, `git show`, or the file the user points
   at) and read it end to end before saying anything.
2. Open the files the diff touches around the changed lines. A diff hides
   the context that decides whether a change is right.
3. Find out what the change is FOR — the branch name, the commit message,
   the description. A change that does its job oddly is a different finding
   from one that does the wrong job.

## What to look for, in this order

1. **Correctness** — what input makes this wrong? Name the case, do not say
   "might break".
2. **The seam it sits in** — does it honour the conventions of the code
   around it, or invent a second way of doing something the project already
   does one way?
3. **Tests** — is the behaviour that changed covered? A new branch nobody
   exercises is an untested branch.
4. **Readability** — names, dead code, comments that no longer match.

## How to report

- One finding per point, most serious first, each with the file and line.
- Say what breaks and under which input; skip the ones you cannot make
  concrete.
- Praise nothing by default and criticise nothing twice.
- If the change is fine, say so plainly and stop.
