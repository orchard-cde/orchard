# Contributing to Orchard

Contributions are welcome. Please open an issue or pull request and follow the conventions below.

## UI Bundle

The Canopy web UI is consumed as a pre-built static bundle. Its version is pinned in
`gradle.properties`:

```properties
orchardUiBundleVersion=0.1.0
```

To consume a newer orchard-ui release, bump that one line and open a PR.

### Pre-seeding the bundle (while orchard-ui is a private repo)

The `:trellis` build downloads `orchard-ui-bundle-<version>.tar.gz` anonymously from
the orchard-ui GitHub Releases page. While the orchard-ui repository is private, the
anonymous download returns a 403, so the bundle must be pre-seeded locally before
building.

Place both files into `trellis/build/ui-bundle/` (this directory is gitignored):

```
trellis/build/ui-bundle/
├── orchard-ui-bundle-0.1.0.tar.gz
└── checksums-sha256.txt
```

Download both files from the orchard-ui GitHub Releases page for the matching version (`v<version>`).

Once orchard-ui is public, the build downloads the bundle anonymously with no pre-seed
required.
