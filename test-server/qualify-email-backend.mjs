import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';

const base = process.env.HARNESS_URL;
assert(base, 'HARNESS_URL is required');
const headers = { 'content-type': 'application/json', 'st-auth-mode': 'header' };

async function request(path, { method = 'GET', body, token, rid } = {}) {
  const response = await fetch(base + path, {
    method, headers: { ...headers, ...(rid ? { rid } : {}), ...(token ? { authorization: `Bearer ${token}` } : {}) },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }), signal: AbortSignal.timeout(10000),
  });
  return { response, data: await response.json() };
}

async function signIn(email) {
  const start = await request('/auth/signinup/code', { method: 'POST', body: { email }, rid: 'passwordless' });
  assert.equal(start.data.status, 'OK');
  const capture = await request(`/captures/latest?email=${encodeURIComponent(email)}`);
  const consume = await request('/auth/signinup/code/consume', {
    method: 'POST', rid: 'passwordless',
    body: { preAuthSessionId: start.data.preAuthSessionId, deviceId: start.data.deviceId, userInputCode: capture.data.userInputCode },
  });
  assert.equal(consume.data.status, 'OK');
  const token = consume.response.headers.get('st-access-token');
  assert(token, 'sign-in did not return a header-mode session');
  const claims = JSON.parse(Buffer.from(token.split('.')[1], 'base64url').toString());
  return { token, handle: claims.sessionHandle, expires: claims.exp };
}

async function session(handle) {
  return request(`/test/email-change-session?handle=${encodeURIComponent(handle)}`);
}

const id = randomUUID();
const oldEmail = `host-old-${id}@example.com`;
const newEmail = `host-new-${id}@example.com`;
const original = await signIn(oldEmail);
const before = await session(original.handle);
assert.equal(before.response.status, 200);
const update = await request('/auth/plugin/rownd/user', { method: 'PUT', token: original.token, rid: 'session', body: { data: { email: newEmail } } });
assert.equal(update.response.status, 200);
const capture = await request(`/captures/latest?email=${encodeURIComponent(newEmail)}`);
const link = new URL(capture.data.emailVerifyLink);
const pendingId = link.searchParams.get('rowndPendingVerificationId');
const verificationToken = link.searchParams.get('token');
assert(pendingId && verificationToken, 'verification capture missing required parameters');
const verificationPath = `/auth/user/email/verify?rowndPendingVerificationId=${encodeURIComponent(pendingId)}`;
const verify = token => request(verificationPath, { method: 'POST', token, rid: 'emailverification', body: { token: verificationToken } });
const anonymous = await verify();
assert.notEqual(anonymous.data.status, 'OK', 'independent unauthenticated verification unexpectedly succeeded');
const independent = await signIn(oldEmail);
assert.notEqual(independent.handle, original.handle);
const other = await verify(independent.token);
assert.notEqual(other.data.status, 'OK', 'independent session unexpectedly verified initiating session draft');
assert.equal(anonymous.data.message, 'email change verification requires the initiating session');
assert.equal(other.data.message, 'email change verification requires the initiating session');
const accepted = await verify(original.token);
assert.equal(accepted.data.status, 'OK', 'initiating session verification must succeed');
const replacementToken = accepted.response.headers.get('st-access-token');
assert(replacementToken, 'verification did not return a replacement session');
const replacement = JSON.parse(Buffer.from(replacementToken.split('.')[1], 'base64url').toString());
const oldState = await session(original.handle);
const newState = await session(replacement.sessionHandle);
assert.equal(newState.response.status, 200);
assert.equal(newState.data.userId, before.data.userId);
assert(newState.data.loginMethods.some(method => method.email === newEmail));
const ttlControl = await signIn(`host-ttl-${id}@example.com`);
const initialProtected = await request('/test/protected', { token: ttlControl.token, rid: 'session' });
assert.equal(initialProtected.response.status, 200, 'TTL control session must initially work');
const observedLifetime = ttlControl.expires - Math.floor(Date.parse(initialProtected.response.headers.get('date')) / 1000);
assert(observedLifetime > 0 && observedLifetime <= 120, 'Core did not issue a short-lived access token');
const deadline = Date.now() + 150000;
let clock;
do {
  if (Date.now() > deadline) throw new Error('server clock did not cross token expiry within 150 seconds');
  await new Promise(resolve => setTimeout(resolve, 500));
  clock = await request('/health');
} while (Date.parse(clock.response.headers.get('date')) / 1000 <= ttlControl.expires);
const expired = await request('/test/protected', { token: ttlControl.token, rid: 'session' });
assert.equal(expired.response.status, 401, 'Core must reject the expired access token');
console.log(JSON.stringify({
  anonymousVerification: anonymous.data.status,
  otherSessionVerification: other.data.status,
  independentVerificationError: anonymous.data.message,
  verificationLinkParameterNames: [...link.searchParams.keys()].sort(),
  initiatingSessionVerification: accepted.data.status,
  originalSessionStillExists: oldState.response.status === 200,
  replacementSameUser: newState.data.userId === before.data.userId,
  canonicalLoginMethods: newState.data.loginMethods.map(method => ({ recipeId: method.recipeId, email: method.email === newEmail ? 'new' : method.email === oldEmail ? 'old' : 'other', verified: method.verified })),
  observedAccessTokenLifetimeSeconds: observedLifetime,
  expiredTokenRejected: true,
}, null, 2));
