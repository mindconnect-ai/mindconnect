- **admin-ui:** **a sidebar group an extension creates can have an icon.**
  A group made from manifest menu entries (Office, for one) was always drawn
  without an icon; the manifest had no place to name one. A
  `contributes.ui.menu` entry now takes `groupIcon`, and the first entry of
  a group that names one gives the group its icon.
