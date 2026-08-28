# ☁️ S3 File Panel

An official [Nuclr Commander](https://nuclr.dev) plugin that adds an `S3` drive
entry to the file panel. 🚀 It browses Amazon S3 — and anything that speaks the
S3 API — as an ordinary pane, so buckets sit beside local folders and the usual
function keys do the usual things.

🔒 Secret access keys are **never written to disk**. A profile file holds an
access key id, a region and an endpoint; the secret is entered once per session
and kept in memory only.

📦 The plugin bundles **no runtime dependencies at all**. Request signing,
XML parsing and transfer are implemented directly against the JDK, which keeps
the signed bundle around 200 KB instead of the tens of megabytes an SDK would
add — and means every S3-compatible service works through the same code path.

## ✨ What It Does

| Feature | Details |
|---|---|
| 🔑 Four ways to authenticate | Access keys, `~/.aws` profiles, IAM Identity Center (SSO), or the ambient environment |
| 🪣 Bucket browser | Lists the account's buckets, or opens one bucket directly for least-privilege credentials |
| 🗂️ Object browser | Prefixes render as folders, with paged loading (a trailing `Load more…` row) for large listings |
| 📥 Copy out (F5) | Objects and whole folders to a local directory, recreating the tree |
| 📤 Copy in (F5) | Local files and directories into the open prefix, directory structure becoming key structure |
| ⚡ Server-side copy | Between two S3 panes on one profile the bytes never touch your machine |
| ✏️ Rename / move (F6) | In-place rename of an object or a whole prefix |
| 📁 Make folder (F7) | Writes the same zero-byte placeholder the AWS console does |
| 🗑️ Delete (F8) | Objects and recursive prefixes, batched 1,000 keys per request |
| 🔎 Find (Alt+F7) | Wildcard name search, streaming results, openable as a temporary panel |
| 👁️ Quick view / F3 / F4 | Objects download once to a temp file and are served from there afterwards |
| 🧩 Multipart upload | Automatic above 64 MB, with part sizes grown so files of any size go up |
| 🌍 Region discovery | A bucket in another region is learned from the redirect and remembered |
| 🔁 Credential refresh | An expiring SSO or instance-role credential renews mid-session without interrupting you |

## 🔐 Authentication

S3 has no email-and-password login — every request is signed with an access key.
The four modes below cover how people actually hold those keys.

### Access key and secret

The access key id is saved in the profile; the secret is prompted for the first
time you open the profile in a session and cached in memory only. Tick
**temporary credentials** to supply an STS session token alongside it.

Use this for S3-compatible services, for a key issued to you directly, or when
you would rather not have AWS configuration on the machine at all.

### AWS profile (`~/.aws`)

Reads the profile you already have. Plain keys are read straight from
`~/.aws/credentials`; a profile that assumes a role or runs a
`credential_process` is resolved through the AWS CLI, which knows how to do
that. The dropdown in the profile dialog lists the profiles that really exist on
this machine.

Both `AWS_SHARED_CREDENTIALS_FILE` and `AWS_CONFIG_FILE` are honoured.

### IAM Identity Center (SSO)

The one mode with an interactive sign-in. The panel drives
`aws configure export-credentials`, which refreshes the IAM Identity Center
token from the CLI's own cache — so a login you did in a terminal already counts,
and one done here counts there.

When the session has expired the panel says so and offers to sign in, running
`aws sso login` and opening your browser. Requires the **AWS CLI v2** on the
`PATH`.

### Environment / instance role

Whatever the machine already provides, in the order every AWS tool uses:
`AWS_ACCESS_KEY_ID` and friends, then a container task role, then the EC2
instance role over IMDSv2. Nothing to configure and nothing to type.

## 🌐 S3-compatible services

Set **Endpoint URL** on the profile and turn on **path-style addressing** (the
dialog offers to do that for you). Tested shapes:

| Service | Endpoint | Region |
|---|---|---|
| MinIO | `https://minio.example.com:9000` | anything; `us-east-1` is fine |
| Cloudflare R2 | `https://<account>.r2.cloudflarestorage.com` | `auto` |
| Wasabi | `https://s3.<region>.wasabisys.com` | the matching region |
| Backblaze B2 | `https://s3.<region>.backblazeb2.com` | the matching region |
| DigitalOcean Spaces | `https://<region>.digitaloceanspaces.com` | the matching region |

## 🧭 Navigation

```
S3                       the drive entry (Alt+F1 / Alt+F2)
└── <profile>            one saved connection
    └── <bucket>         the account's buckets, or straight into a pinned one
        └── <prefix>/    folders, all the way down
            └── object
```

At the profile list: **F4** edit, **F7** new, **F8** remove.
Inside a bucket: the usual **F3** view, **F5** copy, **F6** rename/move,
**F7** make folder, **F8** delete, **Alt+F7** find.

### Least-privilege credentials

Credentials scoped to one bucket usually cannot call `s3:ListAllMyBuckets`, so
the bucket list would fail before you ever reached the bucket. Name the bucket
(and optionally a prefix) in the profile and the panel opens it directly,
skipping the listing that would only have failed.

## 📋 Prerequisites

None for access-key or environment authentication.

The **AWS CLI v2** is required only for SSO profiles and for `~/.aws` profiles
that assume a role or use a `credential_process`. A profile with plain keys
never shells out.

## 🗃️ Where things are stored

| What | Where |
|---|---|
| Connection profiles | `~/.nuclr/s3/profiles.json` — no secrets, safe to sync |
| Secret access keys | Memory only, for the life of the session |
| SSO tokens | The AWS CLI's own cache; this plugin never writes them |
| Viewed objects | A temp file per object, deleted when the plugin unloads |

## 🏗️ Building

```bash
mvn clean verify
```

Produces a signed `target/filepanel-s3-<version>.zip` plus its `.sig`. Signing
needs the keystore at `C:/nuclr/key/nuclr-signing.p12`.

`deploy.bat` builds and copies both files into the commander's `plugins/`
directory.

## 🧪 Tests

```bash
mvn test
```

Request signing is checked against the vectors published with the AWS Signature
Version 4 specification — including the key-derivation vector — because a
signature that is wrong by one byte is rejected with the same opaque
`SignatureDoesNotMatch` as a wrong password, and no amount of retrying helps.

## 📄 License

Apache-2.0. See [LICENSE](LICENSE).
