# Google Play release setup

This project targets Android 16 / API 36 and includes a GitHub Actions workflow that can create a signed Android App Bundle (`.aab`).

## Permanent app identity

- Application ID: `com.saga0003.calculator`
- Version code: `1`
- Version name: `1.0.0`

Do not change the application ID after the first Play Console app is created/uploaded under this ID.

## GitHub signing secrets

The workflow `.github/workflows/build-play-store.yml` expects these repository secrets:

1. `ANDROID_KEYSTORE_BASE64`
2. `ANDROID_KEYSTORE_PASSWORD`
3. `ANDROID_KEY_ALIAS`
4. `ANDROID_KEY_PASSWORD`

Never commit the upload keystore or passwords to the repository.

In GitHub open:

`Repository > Settings > Secrets and variables > Actions > New repository secret`

Create all four secrets using the values from the separately supplied `github-secrets.txt` backup file.

## Build the signed Play Store bundle

After the secrets are configured:

1. Open the repository's **Actions** tab.
2. Select **Build Play Store AAB**.
3. Choose **Run workflow** on the `main` branch.
4. When the workflow succeeds, download the artifact named `calculator-play-store-aab`.
5. The artifact contains `app-release.aab`.

The workflow verifies the AAB signature before uploading the artifact.

## Play Console

Create the app in Google Play Console with the package/application ID `com.saga0003.calculator`, opt in to Play App Signing, and upload the generated `app-release.aab` to the appropriate testing/release track.

For future updates, increment `versionCode` for every Play upload. `versionName` can be changed to a human-readable version such as `1.0.1`.
