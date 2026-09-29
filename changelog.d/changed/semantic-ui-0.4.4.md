- **agents:** **the admin UI renders with semantic-ui 0.4.4** (was 0.4.2). A
  table's row buttons wrap at every width instead of running out of the card
  or cutting off the last button; a button bar can fold into a "⋯" menu
  (`actionsOverflow(Overflow.MENU)` on lists, tables, forms and details);
  charts get a value axis, hover over the whole column, formatted values and
  several series; and a patch on one row of a list now reaches the renderer's
  copy of the tree, so a snapshot after "Apply" on the migrations screen shows
  the new state.
