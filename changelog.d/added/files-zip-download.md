- **agents:** **a folder in the chat's Files dialog downloads as a zip.** Every
  folder, each of the chat's directories included, has a download button that
  packs everything below it into one zip, instead of fetching file by file. The
  zip holds only what the dialog would list, so a link out of the directory
  stays out. A folder with more than 10,000 files or more than 1 GB is refused
  (413). Behind it is `GET /admin/api/sessions/{id}/files/zip?root=…&path=…`,
  for the session's owner only, like the rest of the dialog.
