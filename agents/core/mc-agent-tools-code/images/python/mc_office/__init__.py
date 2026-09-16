"""Office files from a small spec, with nothing but the standard library.

Each module has one entry point, ``build(spec, path)``:

- ``pptx_builder`` — a 16:9 deck: title, bullets, two-column, table, text and
  diagram slides whose arrows are real connectors;
- ``docx_builder`` — a Word document: headings, paragraphs, lists, tables,
  page breaks;
- ``xlsx_builder`` — a workbook: sheets with headers, formats, formulas,
  a frozen header and autofilter.

The spec formats are described by the ``pptx-builder``, ``docx-builder`` and
``xlsx-builder`` skills. Living in the image, the generators do not have to
travel through the model's context on every call.
"""
