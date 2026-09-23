- **common:** **a document table in a schema of its own gets its indexes.**
  `DocumentTable` named an index after its table, schema included
  (`CREATE INDEX … ext_demo_dungeon.dice_roll_…_idx`), which Postgres refuses
  as a syntax error — so an extension that keeps its data in its own schema
  failed the first time it wrote (the demo's dice answered 500). The index is
  now named after the table alone; Postgres puts it in the table's schema.
