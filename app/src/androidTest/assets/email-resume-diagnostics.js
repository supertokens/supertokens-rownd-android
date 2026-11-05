(() => {
  const finite = value => Number.isFinite(value) ? value : null;
  const inspect = element => {
    if (!element) return { present: false };
    const rect = element.getBoundingClientRect();
    const style = getComputedStyle(element);
    return {
      present: true,
      rendered: element.getClientRects().length > 0 && style.display !== 'none' &&
        style.visibility === 'visible' && Number(style.opacity) > 0,
      intersectsViewport: rect.y < window.innerHeight && rect.y + rect.height > 0 &&
        rect.x < window.innerWidth && rect.x + rect.width > 0,
      bounds: [rect.x, rect.y, rect.width, rect.height].map(finite),
    };
  };
  const fixedText = {
    pending: 'Verify your new email',
    reauthenticate: 'Please sign in again to confirm it’s you before changing your email address.',
    confirmation: 'Confirm your email change',
    continueDraft: 'Continue your email change',
    resumeDraft: 'Sign in to continue your email change',
  };
  const fixedSelectors = {
    pending: '[data-testid="rownd-ui-profile-email-verification-pending"]',
    alert: '[role="alert"]',
    identifier: '#rph-sign-in-identifier-input',
    confirmation: '[data-testid="rownd-ui-profile-email-confirmation"]',
    profileEmail: '[data-testid="rownd-ui-profile-field-email"]',
    success: '[data-testid="rownd-ui-email-verification-success"]',
  };
  const paragraphs = Array.from(document.querySelectorAll('h1,h2,h3,p'));
  return {
    ready: ['loading', 'interactive', 'complete'].includes(document.readyState) ? document.readyState : 'other',
    visible: document.visibilityState === 'visible',
    focused: document.hasFocus(),
    activeTag: ['BODY', 'INPUT', 'BUTTON', 'H1', 'H2', 'P'].includes(document.activeElement?.tagName)
      ? document.activeElement.tagName : 'other',
    viewport: [window.innerWidth, window.innerHeight, window.scrollY].map(finite),
    pixelRatio: finite(window.devicePixelRatio),
    visualViewport: window.visualViewport ? [window.visualViewport.width, window.visualViewport.height,
      window.visualViewport.offsetLeft, window.visualViewport.offsetTop, window.visualViewport.scale].map(finite) : null,
    text: Object.fromEntries(Object.entries(fixedText).map(([key, text]) =>
      [key, inspect(paragraphs.find(element => element.textContent?.trim() === text))])),
    selectors: Object.fromEntries(Object.entries(fixedSelectors).map(([key, selector]) =>
      [key, inspect(document.querySelectorAll(selector)[0])])),
  };
})()
