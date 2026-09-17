- **agents:** **the chat's Files dialog opens, edits and deletes files.** A file
  opens in place of the tree: text up to 1 MB in an editor with Save, images
  scaled to fit, PDFs in the browser's viewer; Office documents and other
  binaries are offered as a download. Files and folders can be deleted, a folder
  with everything in it, after a confirmation; a directory of the chat itself
  never can, and a link is removed without touching what it points to. Behind
  it are `GET …/files/view`, `POST …/files/save` and `POST …/files/delete` under
  `/admin/api/sessions/{id}`, for the session's owner only. Workspaces on a
  virtual environment server work the same way; `WorkspaceFiles` gained
  `delete(Path)`.
