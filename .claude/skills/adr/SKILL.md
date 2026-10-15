---
name: adr
description: Record an architecture decision in doc/decisions/ in its format (next number, context, decision, consequences, 10–30 lines), or supersede an existing one. Use when a design question was settled that an agent would otherwise decide again.
disable-model-invocation: true
---
Record the decision described in `$ARGUMENTS` as `doc/decisions/NNNN-<slug>.md`.

1. Read `doc/decisions/README.md` for the format and the rules, and list the
   existing decisions. If one already covers the question, change that one or
   mark it `superseded by NNNN` and write the new one; a decision is never
   deleted.
2. Take the next free number. Write 10 to 30 lines, English, present tense, no
   history: a title precise enough to be found by grep, the status
   (`accepted`), the context in two or three sentences, the decision as it
   applies to code (name the modules, packages or types), and the consequences,
   including what an agent must now do or stop doing. If the input leaves the
   decision ambiguous, state the reading you chose at the top of your reply
   instead of asking.
3. Link it: a line in the index of `doc/decisions/README.md` if there is one,
   and a sentence in `doc/agent/architecture.md` where the decided structure or
   pattern is described, if the decision changes that page. If the decision
   should also be a rule, say so and point to `/rule`; do not add the rule
   here.
4. One commit: `docs: decision NNNN <title>`. Do not push.

Reply with the number, the title and the decision sentence.
