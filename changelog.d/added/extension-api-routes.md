- **agents:** **an extension can own a REST API under `/api/<id>/`.** Next to
  its screens under `/admin/<id>/` and `/ext/<id>/`, a manifest may now name
  routes under `/api/<id>/**`. They are reached with an API token like the
  shipped API, gated by the route's `roles` for plain users of the namespace,
  and answered 404 where the extension is off; the host never wraps them in the
  admin layout or hands a browser the SPA shell there. A route under the shared
  `/api/` space (such as `/api/jobs/**`) is still reported and ignored, so
  extensions that declared one should move their API to `/api/<id>/`.
