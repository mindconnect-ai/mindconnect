/*
 * Logout has to leave the SPA, and the router will not let it.
 *
 * AdminLayout renders the header's Logout as a plain UiLink, and the framework
 * renders that as <a href="/admin/logout" data-href="/admin/logout">. The
 * EventBus intercepts every data-href click and routes it through fetch,
 * expecting a UiPage back. But /admin/logout answers with a 302 to Keycloak's
 * end-session endpoint on another origin, which a fetch cannot follow into —
 * so the SPA reported "The backend is unreachable" while Spring Security had
 * in fact already destroyed the session. Logged out, plus an error to look at.
 *
 * A capture-phase listener sees the click before the bus's delegated handler
 * and turns it back into what it has to be: an ordinary top-level navigation.
 */
document.addEventListener("click", (event) => {
    const target = event.target;
    const link = target instanceof Element
        ? target.closest('a[href="/admin/logout"]')
        : null;
    if (!link) return;

    // stopImmediatePropagation, not preventDefault alone: the bus listens on a
    // container further up and would otherwise still SPA-route the click.
    event.preventDefault();
    event.stopImmediatePropagation();
    window.location.assign("/admin/logout");
}, true);
