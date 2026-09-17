- **agents:** **`code_execute` works in the chat's directories.** Its container's
  `/workspace` used to be a scratch directory of its own under the data
  directory, so a file the code wrote — a chart, a PowerPoint — was out of reach
  of `bash`, the file tools and the user, and an agent telling you where it saved
  it named a path that did not exist. The chat's working directory (its own
  directory unless one was chosen) and its additional directories are now
  mounted writable under their own host paths, so a path means the same inside
  the container and out; the working directory is also `/workspace` and the
  current directory. Only a session without a working directory keeps a scratch
  directory. A chat that changes its directories gets a fresh container on the
  next call, with its files but without packages installed in the old one.
  The bundled `default-chat` no longer mounts your home directory read-only at
  `/mnt/host` for `code_execute`: the container sees the chat's directories and
  nothing else, and its prompt tells the model to save files for you there. The
  bundled definition changes for new installations; an existing `default-chat`
  keeps its stored tool binding until you edit it.
  `code_execute` also describes itself correctly now: which image each language
  runs in and that it carries only the standard library, where files go, that
  installed packages go when the container is recreated, and its time and memory
  limits. The bundled `default-chat` no longer overrides that description with a
  fixed text of its own, which had kept all of it from the model.
