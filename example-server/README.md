# Android Example Backend

Dedicated backend for manually running the Android example app. This is separate from `test-server/`, which is an integration-test harness and may intentionally mock or override app config.

## Run

Start SuperTokens Core:

```bash
docker run -p 127.0.0.1:3567:3567 supertokens/supertokens-postgresql
```

Create env config:

```bash
cp example-server/.env.example example-server/.env
```

Update `example-server/.env`:

```text
API_DOMAIN=https://your-ngrok-domain.ngrok-free.dev
ALLOWED_ORIGINS=https://staging.supertokens-rownd-hub.pages.dev,http://127.0.0.1:5173,http://localhost:5173
GOOGLE_CLIENT_ID=<Google Web OAuth client ID>
GOOGLE_CLIENT_SECRET=<Google Web OAuth client secret>
ROWND_APP_KEY=<local app key>
ROWND_APP_SECRET=<local app secret>
ROWND_MOBILE_CLIENT_DOMAIN=https://staging.supertokens-rownd-hub.pages.dev/
```

Run the backend from the repo root:

```bash
npm run example:server
```

Expose it with ngrok using the same local port:

```bash
ngrok http 3137
```

## Android App

Point the Android example app at the ngrok URL in `app/build.gradle`:

```gradle
buildConfigField "String", "API_URL", '"https://your-ngrok-domain.ngrok-free.dev"'
```

The backend returns app config with email, phone, Google, and guest enabled. Google uses the Web OAuth client ID in app config; the Android package/SHA OAuth client remains a Google Console registration only.
