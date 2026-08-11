import RowndMigrationPlugin, {
  setRowndClient,
} from "@supertokens-plugins/rownd-nodejs";
import cors from "cors";
import express from "express";
import type { Server } from "http";
import { generateKeyPairSync } from "node:crypto";
import { SignJWT, jwtVerify } from "jose";
import SuperTokens from "supertokens-node";
import { errorHandler, middleware } from "supertokens-node/framework/express";
import Passwordless from "supertokens-node/recipe/passwordless";
import Session from "supertokens-node/recipe/session";
import { verifySession } from "supertokens-node/recipe/session/framework/express";
import ThirdParty from "supertokens-node/recipe/thirdparty";
import UserMetadata from "supertokens-node/recipe/usermetadata";
import {
  GenericContainer,
  Network,
  type StartedNetwork,
  type StartedTestContainer,
  Wait,
} from "testcontainers";

type MagicLinkCapture = {
  email?: string;
  phoneNumber?: string;
  urlWithLinkCode?: string;
  userInputCode?: string;
};

type HarnessCounters = {
  legacyRefresh: number;
  migrate: number;
  stRefresh: number;
};

type RequestCapture = {
  method: string;
  path: string;
  authorizationHeaderCount: number;
  hasAppKey: boolean;
};

type HarnessState = {
  captures: Map<string, MagicLinkCapture>;
  legacySessions: Map<string, LegacySessionRecord>;
  counters: HarnessCounters;
  requestLog: RequestCapture[];
  refreshSimulationCompleted: boolean;
  userData: Record<string, unknown>;
  pendingEmailVerifications: Map<string, PendingEmailVerification>;
};

type PendingEmailVerification = {
  token: string;
  userId: string;
  verified: boolean;
};

export type AndroidIntegrationHarness = {
  /** URL reachable from the host machine (e.g. CI scripts, Gradle tasks). */
  serverUrl: string;
  /** URL reachable from the Android emulator (10.0.2.2 loopback alias). */
  androidUrl: string;
  /** Public URL reachable from HTTPS hub pages, typically an ngrok tunnel. */
  publicUrl: string;
  /** Hub server URL reachable from the Android emulator. */
  hubUrl: string;
  appKey: string;
  appId: string;
  stop: () => Promise<void>;
};

export const HARNESS_PORT = Number(process.env.ANDROID_HARNESS_PORT || 3137);

// Address the Android emulator uses to reach the host machine.
// Override ANDROID_HOST when running on a physical device or in CI.
export const ANDROID_HOST = process.env.ANDROID_HOST || "10.0.2.2";

const HUB_URL =
  process.env.ANDROID_HUB_URL ||
  process.env.HUB_URL ||
  "https://staging.supertokens-rownd-hub.pages.dev";
const ANDROID_PUBLIC_API_URL = process.env.ANDROID_PUBLIC_API_URL;

const appName = "Rownd Android Integration Tests";
const APP_ID = "app_test_rownd_android";
const APP_KEY = "test_app_key";
const GOOGLE_CLIENT_ID =
  "302008722349-es448rmfhitknlc5thiomvg3s67o0llo.apps.googleusercontent.com";
const GOOGLE_CLIENT_SECRET = "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";
const ROWND_JWT_SECRET = new TextEncoder().encode(
  "rownd-e2e-secret-rownd-e2e-secret",
);

type LegacySessionRecord = {
  userId: string;
  email?: string;
  refreshToken: string;
};

let network: StartedNetwork | undefined;
let postgresContainer: StartedTestContainer | undefined;
let coreContainer: StartedTestContainer | undefined;
let server: Server | undefined;

const harnessStates = new Map<string, HarnessState>();

function createEmptyState(): HarnessState {
  return {
    captures: new Map<string, MagicLinkCapture>(),
    legacySessions: new Map<string, LegacySessionRecord>(),
    counters: { legacyRefresh: 0, migrate: 0, stRefresh: 0 },
    requestLog: [],
    refreshSimulationCompleted: false,
    userData: { user_id: "harness-user", email: "harness-user@example.com" },
    pendingEmailVerifications: new Map(),
  };
}

function recordRequest(req: express.Request) {
  const authorization = req.headers.authorization;
  const authorizationHeaderCount = Array.isArray(authorization)
    ? authorization.length
    : authorization
      ? 1
      : 0;
  getState(req).requestLog.push({
    method: req.method,
    path: req.path,
    authorizationHeaderCount,
    hasAppKey: typeof req.headers["x-rownd-app-key"] === "string",
  });
}

function getNamespace(req: express.Request): string {
  return (
    (typeof req.headers["x-rownd-e2e-namespace"] === "string" &&
      req.headers["x-rownd-e2e-namespace"]) ||
    (typeof req.query.namespace === "string" && req.query.namespace) ||
    (typeof req.body?.namespace === "string" && req.body.namespace) ||
    "default"
  );
}

function getState(req: express.Request): HarnessState {
  const namespace = getNamespace(req);
  if (!harnessStates.has(namespace)) {
    harnessStates.set(namespace, createEmptyState());
  }
  return harnessStates.get(namespace)!;
}

function resetState(namespace?: string) {
  if (!namespace) {
    harnessStates.clear();
    return;
  }
  harnessStates.set(namespace, createEmptyState());
}

function getCapture(state: HarnessState, email?: string, phoneNumber?: string) {
  const key = email || phoneNumber || "";
  return state.captures.get(key);
}

function findCapture(
  req: express.Request,
  email?: string,
  phoneNumber?: string,
) {
  const state = getState(req);
  const hit = getCapture(state, email, phoneNumber);
  if (hit) return hit;

  const defaultHit = getCapture(
    harnessStates.get("default") || createEmptyState(),
    email,
    phoneNumber,
  );
  if (defaultHit) return defaultHit;

  for (const s of harnessStates.values()) {
    const c = getCapture(s, email, phoneNumber);
    if (c) return c;
  }
  return undefined;
}

function getAllCounters() {
  return Array.from(harnessStates.values()).reduce(
    (acc, s) => {
      acc.legacyRefresh += s.counters.legacyRefresh;
      acc.migrate += s.counters.migrate;
      acc.stRefresh += s.counters.stRefresh;
      return acc;
    },
    { legacyRefresh: 0, migrate: 0, stRefresh: 0 },
  );
}

function findLegacySessionByRefreshToken(refreshToken: string) {
  for (const [, state] of harnessStates.entries()) {
    const record = state.legacySessions.get(refreshToken);
    if (record) return { state, record };
  }
  return undefined;
}

function base64UrlEncode(value: Uint8Array) {
  return Buffer.from(value).toString("base64url");
}

async function makeLegacyAccessToken(
  userId: string,
  appId: string,
  expiresInSeconds: number,
  email?: string,
) {
  const nowSeconds = Math.floor(Date.now() / 1000);
  return new SignJWT({
    email,
    "https://auth.rownd.io/app_user_id": userId,
    "https://auth.rownd.io/is_verified_user": true,
  })
    .setProtectedHeader({ alg: "HS256", typ: "JWT" })
    .setIssuedAt(nowSeconds)
    .setExpirationTime(nowSeconds + expiresInSeconds)
    .setAudience(`app:${appId}`)
    .setSubject(userId)
    .setIssuer("https://api.rownd.io")
    .sign(ROWND_JWT_SECRET);
}

function makeLegacyRefreshToken() {
  return base64UrlEncode(crypto.getRandomValues(new Uint8Array(24)));
}

async function ensureSTUser(
  coreConnectionURI: string,
  userId: string,
  email?: string,
) {
  const existing = await SuperTokens.getUser(userId);
  if (existing) return existing;

  const loginMethods = email
    ? [{ recipeId: "passwordless", email, isVerified: true }]
    : [
        {
          recipeId: "passwordless",
          phoneNumber: "+15555550123",
          isVerified: true,
        },
      ];

  const response = await fetch(`${coreConnectionURI}/bulk-import/import`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      externalUserId: userId,
      loginMethods,
      userMetadata: {
        data: { user_id: userId, ...(email ? { email } : {}) },
        meta: {},
        verified_data: { ...(email ? { email } : {}) },
        attributes: {},
        rownd_migrated: true,
        rownd_user_id: userId,
      },
    }),
  });

  if (!response.ok) {
    throw new Error(
      `Failed to import ST user: ${response.status} ${await response.text()}`,
    );
  }

  const payload = (await response.json()) as {
    status: string;
    user?: { id: string; loginMethods: Array<{ recipeUserId: string }> };
  };

  if (payload.status !== "OK" || !payload.user) {
    throw new Error("Bulk import failed for startup migration harness");
  }

  return SuperTokens.getUser(payload.user.id);
}

export async function startIntegrationHarness(): Promise<AndroidIntegrationHarness> {
  resetState();

  const { privateKey: applePrivateKey } = generateKeyPairSync("ec", {
    namedCurve: "P-256",
  });
  const testApplePrivateKey = applePrivateKey
    .export({ type: "sec1", format: "pem" })
    .toString();

  network = await new Network().start();
  postgresContainer = await new GenericContainer("postgres:14")
    .withNetwork(network)
    .withNetworkAliases("postgres")
    .withEnvironment({
      POSTGRES_USER: "supertokens",
      POSTGRES_PASSWORD: "somepassword",
      POSTGRES_DB: "supertokens",
    })
    .withExposedPorts(5432)
    .withWaitStrategy(
      Wait.forLogMessage("database system is ready to accept connections"),
    )
    .start();

  coreContainer = await new GenericContainer(
    "supertokens/supertokens-postgresql",
  )
    .withNetwork(network)
    .withEnvironment({
      POSTGRESQL_CONNECTION_URI:
        "postgresql://supertokens:somepassword@postgres:5432/supertokens",
    })
    .withExposedPorts(3567)
    .withWaitStrategy(Wait.forHttp("/hello", 3567))
    .start();

  const coreConnectionURI = `http://${coreContainer.getHost()}:${coreContainer.getMappedPort(3567)}`;
  const app = express();

  const started = await new Promise<{ server: Server; port: number }>(
    (resolve, reject) => {
      // Bind to all interfaces so the emulator can reach the host at 10.0.2.2
      const listeningServer = app.listen(HARNESS_PORT, "0.0.0.0", () => {
        const address = listeningServer.address();
        if (!address || typeof address === "string") {
          throw new Error(
            "Could not determine Android integration harness port",
          );
        }
        resolve({ server: listeningServer, port: address.port });
      });
      listeningServer.once("error", (error: NodeJS.ErrnoException) => {
        if (error.code === "EADDRINUSE") {
          reject(
            new Error(
              `Android integration harness port ${HARNESS_PORT} is already in use`,
            ),
          );
          return;
        }

        reject(error);
      });
    },
  );

  server = started.server;

  const serverUrl = `http://127.0.0.1:${started.port}`;
  const androidUrl = `http://${ANDROID_HOST}:${started.port}`;
  const publicUrl = ANDROID_PUBLIC_API_URL || androidUrl;
  const hubUrl = HUB_URL;

  SuperTokens.init({
    debug: true,
    supertokens: { connectionURI: coreConnectionURI },
    appInfo: {
      appName,
      apiDomain: publicUrl,
      websiteDomain: hubUrl,
    },
    recipeList: [
      Session.init(),
      UserMetadata.init(),
      ThirdParty.init({
        signInAndUpFeature: {
          providers: [
            {
              config: {
                thirdPartyId: "google",
                clients: [
                  {
                    clientId: GOOGLE_CLIENT_ID,
                    clientSecret: GOOGLE_CLIENT_SECRET,
                  },
                ],
              },
            },
            {
              config: {
                thirdPartyId: "apple",
                clients: [
                  {
                    clientId: "test-apple-client-id",
                    additionalConfig: {
                      teamId: "TESTTEAM01",
                      keyId: "TESTKEY0001",
                      privateKey: testApplePrivateKey,
                    },
                  },
                ],
              },
            },
          ],
        },
      }),
      Passwordless.init({
        contactMethod: "EMAIL_OR_PHONE",
        flowType: "MAGIC_LINK",
        emailDelivery: {
          service: {
            sendEmail: async (input: any) => {
              const state = harnessStates.get("default") || createEmptyState();
              harnessStates.set("default", state);
              state.captures.set(input.email, {
                email: input.email,
                urlWithLinkCode: input.urlWithLinkCode,
                userInputCode: input.userInputCode,
              });
            },
          },
        },
        smsDelivery: {
          service: {
            sendSms: async (input: any) => {
              const state = harnessStates.get("default") || createEmptyState();
              harnessStates.set("default", state);
              state.captures.set(input.phoneNumber, {
                phoneNumber: input.phoneNumber,
                urlWithLinkCode: input.urlWithLinkCode,
                userInputCode: input.userInputCode,
              });
            },
          },
        },
      }),
    ],
    experimental: {
      plugins: [
        RowndMigrationPlugin.init({
          enableDebugLogs: true,
          rowndAppKey: APP_KEY,
          rowndAppSecret: "rownd-e2e-secret-rownd-e2e-secret",
          clientDomains: {
            mobile: "https://staging.supertokens-rownd-hub.pages.dev/",
          },
          schema: {
            nickname: {
              display_name: "Nickname",
              type: "string",
              user_visible: true,
            },
          },
          appConfig: {
            id: APP_ID,
            name: appName,
            signInMethods: [
              { method: "google", iosClientId: "test-google-ios-client-id" },
              { method: "phone" },
              { method: "email" },
              { method: "anonymous" },
            ],
          },
        }),
      ],
    },
  });

  setRowndClient({
    validateToken: async (token: string) => {
      const { payload } = await jwtVerify(token, ROWND_JWT_SECRET);
      const userId =
        (payload as any)["https://auth.rownd.io/app_user_id"] || payload.sub;
      if (!userId) throw new Error("Missing user id in token");
      return { user_id: userId as string };
    },
    fetchUserInfo: async ({ user_id }: { user_id: string }) =>
      ({
        state: "enabled",
        auth_level: "verified",
        data: { user_id, email: `${user_id}@example.com` },
        verified_data: { email: `${user_id}@example.com` },
        groups: [],
        meta: {},
      }) as any,
  });

  // The hub runs in a WebView and uses credentialed fetches, so CORS must echo
  // the concrete Origin instead of using "*".
  app.use(
    cors({
      origin: true,
      allowedHeaders: [
        "content-type",
        "authorization",
        "rid",
        "fdi-version",
        "st-auth-mode",
        "anti-csrf",
        "x-rownd-app-key",
        "x-rownd-e2e-namespace",
      ],
      exposedHeaders: [
        "front-token",
        "st-access-token",
        "st-refresh-token",
        "anti-csrf",
      ],
      credentials: true,
    }),
  );
  app.use(express.json());

  app.get("/test/email-verification-launcher", (req, res) => {
    const names = [
      "token",
      "rowndPendingVerificationId",
      "apiDomain",
      "apiBasePath",
    ];
    const params = new URLSearchParams();
    for (const name of names) {
      const value = req.query[name];
      if (typeof value === "string") params.set(name, value);
    }
    const href = `rowndsupertokens://account/verify-email?${params.toString()}`;
    const escapedHref = href
      .replaceAll("&", "&amp;")
      .replaceAll('"', "&quot;")
      .replaceAll("<", "&lt;")
      .replaceAll(">", "&gt;");

    res.type("html").send(
      `<!doctype html><meta name="viewport" content="width=device-width"><a href="${escapedHref}">Open email verification</a>`,
    );
  });

  // Counter tracking
  app.use((req, _res, next) => {
    const state = getState(req as express.Request);
    if (req.method === "POST" && req.path === "/auth/session/refresh") {
      state.counters.stRefresh += 1;
    }
    if (req.method === "POST" && req.path === "/auth/plugin/rownd/migrate") {
      state.counters.migrate += 1;
    }
    if (req.path === "/auth/plugin/rownd/user") {
      recordRequest(req as express.Request);
    }
    next();
  });

  // App config — the hub runs in a WebView, so HTTPS hub pages need a public HTTPS
  // apiDomain instead of the emulator loopback URL to avoid mixed-content blocking.
  app.get("/auth/plugin/rownd/app-config", (_req, res) => {
    res.json({
      app: {
        id: APP_ID,
        icon: "",
        user_verification_fields: [],
        schema: {
          nickname: {
            display_name: "Nickname",
            type: "string",
            user_visible: true,
            owned_by: "user",
            read_only: false,
          },
        },
        config: {
          hub: {
            auth: {
              sign_in_methods: {
                email: { enabled: true },
                phone: { enabled: true },
                google: { enabled: true, client_id: GOOGLE_CLIENT_ID },
                anonymous: { enabled: false },
              },
            },
          },
          customizations: {},
          supertokens: {
            appInfo: {
              apiDomain: publicUrl,
              apiBasePath: "/auth",
            },
          },
        },
      },
    });
  });

  // Config endpoint — consumed by Android tests to configure the SDK at test startup
  app.get("/config", (_req, res) => {
    res.json({
      serverUrl,
      androidUrl,
      publicUrl,
      hubUrl,
      appId: APP_ID,
      appKey: APP_KEY,
      supertokens: {
        appInfo: {
          apiDomain: publicUrl,
          apiBasePath: "/auth",
        },
      },
    });
  });

  // Mock ThirdParty sign-in — intercept before ST middleware to avoid real OAuth calls
  const completeThirdPartySignInUp = async (req: any, res: any) => {
    const thirdPartyId =
      typeof req.body?.thirdPartyId === "string"
        ? req.body.thirdPartyId
        : "google";
    const token =
      req.body?.oAuthTokens?.id_token ||
      req.body?.oAuthTokens?.access_token ||
      req.body?.redirectURIInfo?.redirectURIQueryParams?.code;
    const suffix =
      typeof token === "string" && token.length > 0
        ? token.replace(/[^a-zA-Z0-9]/g, "").slice(-12)
        : `${thirdPartyId}callback`;
    const userId = `${thirdPartyId}-${suffix || "user"}`;
    const email = `${userId}@example.com`;

    const user = await ensureSTUser(coreConnectionURI, userId, email);
    const recipeUserId = user?.loginMethods[0]?.recipeUserId;
    if (!recipeUserId) {
      res.status(500).json({
        status: "GENERAL_ERROR",
        message: `No recipe user found for ${thirdPartyId}`,
      });
      return;
    }

    await Session.createNewSession(
      req,
      res,
      "public",
      recipeUserId,
      {},
      {},
      {},
    );
    const accessToken =
      typeof res.getHeader("st-access-token") === "string"
        ? res.getHeader("st-access-token")
        : null;
    res.json({
      status: "OK",
      createdNewRecipeUser: false,
      user: { id: user.id },
      rownd: {
        user_type: "existing_user",
        app_id: APP_ID,
        app_user_id: userId,
      },
      access_token: accessToken,
    });
  };

  app.post("/auth/signinup", completeThirdPartySignInUp);
  app.post("/auth/public/signinup", completeThirdPartySignInUp);

  app.post(
    "/auth/plugin/rownd/signout",
    verifySession() as any,
    async (req: any, res) => {
      const signOutAll = req.body?.sign_out_all === true;

      if (signOutAll) {
        await Session.revokeAllSessionsForUser(req.session.getUserId());
      } else {
        await Session.revokeSession(req.session.getHandle());
      }

      res.json({ sign_out_all: signOutAll });
    },
  );

  app.post(
    "/auth/user/email/verify",
    verifySession() as any,
    async (req: any, res) => {
      const pendingId = String(req.query.rowndPendingVerificationId || "");
      const pending = getState(req).pendingEmailVerifications.get(pendingId);
      if (
        !pending ||
        pending.verified ||
        pending.token !== req.body?.token ||
        req.body?.method !== "token" ||
        pending.userId !== req.session.getUserId()
      ) {
        res.status(400).json({ status: "GENERAL_ERROR" });
        return;
      }

      pending.verified = true;
      await Session.createNewSession(
        req,
        res,
        "public",
        req.session.getRecipeUserId(),
        {},
        {},
        {},
      );
      res.json({ status: "OK" });
    },
  );

  app.use(middleware());

  app.get("/auth/plugin/rownd/user", verifySession() as any, (req, res) => {
    res.json({
      state: "enabled",
      auth_level: "verified",
      data: getState(req).userData,
      verified_data: {},
      redacted: [],
      groups: [],
      meta: {},
    });
  });

  app.put("/auth/plugin/rownd/user", verifySession() as any, (req, res) => {
    const state = getState(req);
    const data =
      typeof req.body?.data === "object" && req.body.data !== null
        ? req.body.data
        : {};
    state.userData = { ...state.userData, ...data };

    res.json({
      state: "enabled",
      auth_level: "verified",
      data: state.userData,
      verified_data: {},
      redacted: [],
      groups: [],
      meta: {},
    });
  });

  app.get("/health", (_req, res) => {
    res.json({ status: "OK" });
  });

  app.post("/reset", (req, res) => {
    resetState(getNamespace(req));
    res.json({ status: "OK" });
  });

  // Legacy Rownd token refresh — only used during startup migration
  app.post("/hub/auth/token", async (req, res) => {
    const state = getState(req);
    state.counters.legacyRefresh += 1;
    const refreshToken = req.body?.refresh_token;
    const match =
      typeof refreshToken === "string"
        ? findLegacySessionByRefreshToken(refreshToken)
        : undefined;

    if (!refreshToken || !match) {
      res.status(400).json({ message: "Invalid refresh token" });
      return;
    }

    const { state: targetState, record } = match;
    const nextRefreshToken = makeLegacyRefreshToken();
    targetState.legacySessions.delete(refreshToken);
    targetState.legacySessions.set(nextRefreshToken, {
      ...record,
      refreshToken: nextRefreshToken,
    });

    const accessToken = await makeLegacyAccessToken(
      record.userId,
      APP_ID,
      3600,
      record.email,
    );
    res.json({ access_token: accessToken, refresh_token: nextRefreshToken });
  });

  // Test management endpoints — called from HarnessClient.kt on the emulator

  app.post("/test/legacy-session", async (req, res) => {
    const state = getState(req);
    const userId = String(req.body?.userId || "legacy-user");
    const email =
      typeof req.body?.email === "string"
        ? req.body.email
        : `${userId}@example.com`;
    const expired = req.body?.expired !== false;
    const refreshToken = makeLegacyRefreshToken();

    state.legacySessions.set(refreshToken, { userId, email, refreshToken });

    const accessToken = await makeLegacyAccessToken(
      userId,
      APP_ID,
      expired ? -3600 : 3600,
      email,
    );
    res.json({
      access_token: accessToken,
      refresh_token: refreshToken,
      app_id: APP_ID,
      user_id: userId,
    });
  });

  app.get("/counters", (req, res) => {
    res.json(getState(req).counters);
  });

  app.get("/counters/all", (_req, res) => {
    res.json(getAllCounters());
  });

  app.get("/test/last-request", (req, res) => {
    const path = String(req.query.path || "");
    const request = [...getState(req).requestLog]
      .reverse()
      .find((entry) => !path || entry.path === path);

    if (!request) {
      res.status(404).json({ error: "Request not found" });
      return;
    }

    res.json(request);
  });

  app.get("/captures/latest", (req, res) => {
    const email = String(req.query.email || "");
    const phoneNumber = String(req.query.phoneNumber || "");
    const capture = findCapture(req, email, phoneNumber);

    if (!capture) {
      res.status(404).json({ error: "Capture not found" });
      return;
    }

    res.json(capture);
  });

  app.post("/test/st-session", async (req, res) => {
    const userId = String(req.body?.userId || `test-user-${Date.now()}`);
    const email = `${userId}@example.com`;

    const user = await ensureSTUser(coreConnectionURI, userId, email);
    const recipeUserId = user?.loginMethods[0]?.recipeUserId;
    if (!recipeUserId) {
      res.status(500).json({
        status: "GENERAL_ERROR",
        message: `No recipe user found for ${userId}`,
      });
      return;
    }

    await Session.createNewSession(
      req,
      res,
      "public",
      recipeUserId,
      {},
      {},
      {},
    );
    const accessToken =
      typeof res.getHeader("st-access-token") === "string"
        ? (res.getHeader("st-access-token") as string)
        : "";
    const refreshToken =
      typeof res.getHeader("st-refresh-token") === "string"
        ? (res.getHeader("st-refresh-token") as string)
        : "";
    res.json({
      access_token: accessToken,
      refresh_token: refreshToken,
      user_id: userId,
    });
  });

  app.post("/test/pending-email-verification", (req, res) => {
    const userId = String(req.body?.userId || "test-user");
    const pendingVerificationId = `pending-${Date.now()}`;
    const token = `verify-${Date.now()}`;
    getState(req).pendingEmailVerifications.set(pendingVerificationId, {
      token,
      userId,
      verified: false,
    });
    res.json({ token, pendingVerificationId, userId });
  });

  app.get("/test/protected", verifySession() as any, async (req: any, res) => {
    res.json({
      userId: req.session.getUserId(),
      accessTokenPayload: req.session.getAccessTokenPayload(),
    });
  });

  app.post("/test/refresh/reset", (req, res) => {
    const state = getState(req);
    state.refreshSimulationCompleted = false;
    res.json({ status: "OK", refreshSimulationCompleted: false });
  });

  app.get(
    "/test/refresh",
    (req, res, next) => {
      const state = getState(req);
      if (!state.refreshSimulationCompleted) {
        state.refreshSimulationCompleted = true;
        res.status(401).json({
          status: "REFRESH_REQUIRED",
          message: "Forced 401 to test SuperTokens session refresh",
        });
        return;
      }

      next();
    },
    verifySession() as any,
    async (req: any, res) => {
      res.json({
        status: "OK",
        userId: req.session.getUserId(),
        accessTokenPayload: req.session.getAccessTokenPayload(),
        refreshSimulationCompleted: getState(req).refreshSimulationCompleted,
      });
    },
  );

  app.use(errorHandler());

  return {
    serverUrl,
    androidUrl,
    publicUrl,
    hubUrl,
    appKey: APP_KEY,
    appId: APP_ID,
    stop: async () => {
      if (server) {
        const closingServer = server;
        await new Promise<void>((resolve, reject) => {
          closingServer.close((err) => (err ? reject(err) : resolve()));
          closingServer.closeAllConnections();
        });
        server = undefined;
      }
      if (coreContainer) {
        await coreContainer.stop();
        coreContainer = undefined;
      }
      if (postgresContainer) {
        await postgresContainer.stop();
        postgresContainer = undefined;
      }
      if (network) {
        await network.stop();
        network = undefined;
      }
    },
  };
}
