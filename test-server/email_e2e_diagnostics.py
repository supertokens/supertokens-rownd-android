"""Extract only allowlisted diagnostics from potentially sensitive instrumentation output."""
import json
import re


def checkpoint_markers(output):
    markers = []
    allowed_dom = {'readyState', 'origin', 'path', 'count', 'visibleCount', 'tag', 'type', 'visible', 'disabled', 'value', 'callback'}
    allowed_top = {'previousPid', 'currentPid', 'authenticated', 'nativeEmailMatchesCanonical', 'serverSessionStatus',
                   'serverOwnerMatches', 'serverProfileStatus', 'serverEmailMatchesCanonical', 'accessTokenValidLocally'}
    for match in re.finditer(r'EMAIL_E2E_DIAGNOSTIC (\{[^\r\n]*\})', output):
        try:
            raw = json.loads(match.group(1))
        except (ValueError, TypeError):
            continue
        safe = {key: raw[key] for key in allowed_top if key in raw and isinstance(raw[key], (int, bool))}
        if raw.get('phase') in ('entry', 'native', 'rendered', 'complete'):
            safe['phase'] = raw['phase']
        if isinstance(raw.get('apiOrigin'), str) and re.fullmatch(r'http://127\.0\.0\.1:\d{1,5}', raw['apiOrigin']):
            safe['apiOrigin'] = raw['apiOrigin']
        def sanitize_dom(dom):
            allowed_strings = {'loading', 'interactive', 'complete', 'other', 'missing', 'INPUT', 'text', 'email',
                               'canonical', 'old', 'empty', 'parsed', 'unparseable', '/mobile_app', '/account/verify-email'}
            return {key: value for key, value in dom.items() if key in allowed_dom and
                    (isinstance(value, (int, bool)) or isinstance(value, str) and
                     (value in allowed_strings or key == 'origin' and re.fullmatch(r'http://127\.0\.0\.1:\d{1,5}', value)))}
        dom = raw.get('dom')
        if isinstance(dom, dict):
            safe['dom'] = sanitize_dom(dom)
        history = raw.get('history')
        if isinstance(history, list):
            safe['history'] = [sanitize_dom(item) for item in history[:8] if isinstance(item, dict)]
        markers.append(safe)
    return markers
