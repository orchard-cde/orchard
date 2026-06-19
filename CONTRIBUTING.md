# Contributing to Orchard

Contributions are welcome. Please open an issue or pull request and follow the conventions below.

## Dev Server (Two-Process Model)

`trowel dev-server start` launches two processes:

- **orchard-server** (core, API only) on port **7778**
- **orchard-ui-backend** (Canopy UI BFF) on port **7777** — serves the Canopy UI and
  reverse-proxies `/api/**` to core

Open **http://localhost:7777** in your browser. Use `--no-ui` to run core only (`:7778`).

### Dev Mode UI Authentication

When running `trowel dev-server start` in dev mode (oauth2 disabled), the UI automatically
authenticates as the cultivator configured in trowel. Requests from the BFF are proxied
through to core, so auto-auth works transparently. To test as a different cultivator,
set the `X-Cultivator-Id` header via browser localStorage.

### orchard-ui-backend Binary

The `orchard-ui-backend` BFF binary is downloaded automatically from orchard-ui GitHub
releases. Until a pre-built binary is published for your platform (macOS builds are not
yet available), build it locally from the sibling `orchard-ui/` checkout:

```bash
./gradlew :backend:nativeCompile
cp backend/build/native/nativeCompile/orchard-ui-backend ~/.orchard/bin/
```
