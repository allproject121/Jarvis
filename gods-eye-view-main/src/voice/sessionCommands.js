import { createVoiceControl } from './control.js';
import { createVoiceSession } from './session.js';
import { VoiceCardControls } from './voiceCard.js';
import { PointerReticle } from './pointerReticle.js';

function createDefaultReticle(pointer) {
  const element = pointer?.element;
  const doc = element?.ownerDocument;
  if (!doc) return null;
  return new PointerReticle({
    doc,
    rectOf: () => element.getBoundingClientRect?.() || null,
  });
}

/** Bind common controls to a supplied voice-session adapter. */
export function createVoiceCommands({
  runner,
  dataManager,
  annotations = null,
  createSession,
  createController,
  backend,
  signal,
  debugSink,
  createControl = createVoiceControl,
  createCard = (options) => new VoiceCardControls(options),
  pointer = null,
  referents = null,
  createReticle = createDefaultReticle,
}) {
  window.__gevVoiceCommands?.stop?.({ removeUi: true });
  const ui = createControl({ reset: true });
  const session = createVoiceSession({
    runner,
    signal,
    createAdapter: (hooks) =>
      createSession({
        ...hooks,
        runner,
        ui,
        dataManager,
        backend,
        debugSink,
        createController,
        pointer,
        radioLayer: dataManager?.layers?.get('radio')?.module || null,
      }),
  });
  const adapter = session.adapter;
  const capabilities = adapter?.capabilities || {};
  const isJarvis = typeof window !== 'undefined' && Boolean(window.JarvisVoiceBridge?.isIntegrated?.());
  if (isJarvis) {
    if (ui.tierButton) ui.tierButton.hidden = true;
    if (ui.costValue) ui.costValue.hidden = true;
    ui.button.setAttribute('aria-label', 'Trigger Jarvis Voice');
    if (ui.helpDetail) ui.helpDetail.textContent = 'Tap to speak with Jarvis · Say "Hey Jarvis"';
    if (ui.status) ui.status.textContent = 'ONLINE';
    if (ui.detail) ui.detail.textContent = 'JARVIS READY';
  } else {
    if (ui.tierButton) ui.tierButton.hidden = !capabilities.costControls;
    if (ui.costValue) ui.costValue.hidden = !capabilities.costControls;
    if (!capabilities.pushToTalk) {
      ui.button.setAttribute('aria-label', 'Toggle voice control');
      if (ui.helpDetail) ui.helpDetail.textContent = 'Activate to toggle voice';
    }
  }
  // Retain the existing controller's inspection surface for browser tools.
  const controls = adapter.controller || session;
  controls.session = session;
  const updateStatus = session.subscribe((event) => {
    if (event.type !== 'state') return;
    if (isJarvis) return; // Jarvis state updates are pushed directly via window.__updateJarvisVoiceStatus
    ui.root.dataset.status = event.state;
    ui.status.textContent =
      event.state === 'idle' ? 'OFF' : event.state.toUpperCase();
    ui.detail.textContent =
      event.detail || (event.state === 'idle' ? 'Voice off' : 'Voice active');
    ui.button.setAttribute('aria-pressed', String(session.isActive()));
    if (ui.errorDetail)
      ui.errorDetail.textContent =
        event.state === 'error'
          ? event.detail || 'Voice could not be started.'
          : '';
    if (event.state === 'error') ui.root.classList?.remove('error-dismissed');
  });
  // Captions, plan and result card nested in the control; it only reads
  // provider-neutral session events.
  const card = ui.card ? createCard({ root: ui.root }) : null;
  const updateCard = card
    ? session.subscribe((event) => card.handle(event))
    : null;
  controls.voiceCard = card;
  // Point-and-ask: the reticle follows turn events; pointer and referents are
  // session-scoped, so a session that ends forgets both.
  const reticle = pointer ? createReticle(pointer) : null;
  const updatePointer = session.subscribe((event) => {
    reticle?.handle(event);
    if (event.type === 'state' && ['idle', 'error'].includes(event.state)) {
      pointer?.clear();
      referents?.clear();
      // Conversation memory ("those", "the last answer") ends with the
      // session.
      runner.resetConversation?.();
    }
  });
  controls.pointer = pointer;
  controls.referents = referents;
  const annotationUnsubscribe = annotations?.onOutlineEvent?.((event) => {
    card?.outline(event);
    session.sendMapEvent({ type: 'map_annotation_outline', ...event });
  });
  const buttonHandler = () => {
    if (typeof window !== 'undefined' && Boolean(window.JarvisVoiceBridge?.isIntegrated?.())) {
      try {
        window.JarvisVoiceBridge.triggerVoice();
      } catch (err) {
        console.warn('[JarvisBridge] triggerVoice error:', err);
      }
      return;
    }
    if (adapter.ignoreButtonClick?.()) return;
    if (session.isActive()) session.stop();
    else void session.start({ pushToTalk: false });
  };
  ui.button.addEventListener('click', buttonHandler);
  session.signal.addEventListener(
    'abort',
    () => {
      ui.button.removeEventListener('click', buttonHandler);
      annotationUnsubscribe?.();
      updateStatus();
      updateCard?.();
      updatePointer();
      reticle?.destroy();
      card?.destroy();
      runner.dispose?.();
      ui.root.remove();
    },
    { once: true },
  );
  if (session.disposed) {
    ui.button.removeEventListener('click', buttonHandler);
    annotationUnsubscribe?.();
    updateStatus();
    updateCard?.();
    updatePointer();
    reticle?.destroy();
    card?.destroy();
    runner.dispose?.();
    ui.root.remove();
  } else if (!isJarvis) {
    adapter.bindControls?.();
  }
  window.__gevVoiceCommands = controls;
  return controls;
}
