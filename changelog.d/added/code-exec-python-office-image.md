- **agents:** **`code_execute` runs python in an image with the office libraries.**
  The default python image is now `ghcr.io/mindconnect-ai/code-exec-python`:
  Python 3.12 with python-pptx, python-docx, openpyxl, matplotlib and pandas, and
  the `mc_office` package whose `pptx_builder`, `docx_builder` and `xlsx_builder`
  build a deck, a Word document or a workbook from a small spec. The office
  skills used to carry those generators as text the model had to copy into every
  call — tens of kilobytes that crowded a local model's context before the first
  slide was written. The tool tells the model what the image carries. It is
  published for amd64 and arm64; `mindconnect.code-exec.languages=python=python:3.12-slim`
  goes back to the plain image.
