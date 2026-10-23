import * as Cesium from 'cesium';
import { SceneDirector } from '../scenes/director.js';
import { initAnnotations } from '../annotations/index.js';
import { initDrawTool } from '../annotations/drawTool.js';
import { initImageryBoxTool } from '../ui/imageryBoxTool.js';
import { createRecentImageryPanel } from '../ui/recentImagery.js';
import { initGevVoiceCommands } from '../voice/gevRealtime.js';
import { installViews, isEmbeddedInline } from './embed.js';
import { installScopeMask, destroyScopeMask } from '../scopeMask.js';
import {
  installRenderGovernor,
  getRenderGovernorDiagnostics,
  governorRequestRender,
  holdContinuousRender,
  releaseContinuousRender,
} from '../renderGovernor.js';

/** Attach scene tools, rendering listeners and the application debug handle. */
export function createApplicationTools({
  scene,
  controls,
  data,
  loadingScreen,
  placeSearch,
  voice = {},
  startChrome,
  onSceneDirector,
  sceneDataPacks,
  signal,
  defer,
}) {
  const { viewer, tileset, mapStackController, operations } = scene;
  const { styleManager, weatherEffects, cockpitCloudEffects } = controls;
  const { dataManager } = data;
  const sceneDirector = new SceneDirector(viewer, styleManager, dataManager, {
    dataPacks: sceneDataPacks,
    isMapStackAvailable: (id) =>
      mapStackController?.isStackAvailable(id) === true,
  });
  dataManager.layers
    .get('bhote-koshi-2026')
    ?.module.attachSceneController(sceneDirector);
  defer(() => sceneDirector.destroy());
  onSceneDirector?.(sceneDirector);
  const annotations = initAnnotations({
    viewer,
    tileset,
    placeSearch,
    resolver: operations.annotationResolver,
  });
  defer(() => {
    if (window.__gevAnnotations === annotations) delete window.__gevAnnotations;
    annotations.destroy();
  });
  // DISPLAY ▸ Draw: the same whiteboard, drawn by hand. It claims the pointer
  // while a session is open, so its teardown belongs to the application
  // lifetime rather than to whoever last pressed the button.
  const drawTool = initDrawTool({ viewer, annotations });
  defer(() => drawTool?.destroy());
  // DATA ▸ Recent Imagery: the box tool claims the pointer like Draw and the
  // panel lives on the right rail, so both belong to the application
  // lifetime. The tileset lets the layer drape while the globe is hidden.
  const recentImagery = dataManager.layers.get('recent-imagery')?.module;
  if (recentImagery) {
    recentImagery.attachTileset(tileset);
    const imageryBoxTool = initImageryBoxTool({
      viewer,
      onBox: (box) => recentImagery.setBox(box),
      onCancel: (reason, message, box) => {
        if (message) recentImagery.reportBoxRefusal(message, box);
      },
      onActive: (active) => recentImagery.setToolActive(active),
      // The tool takes Escape in a capture listener, so the panel's order
      // (clear a preview before cancelling the tool) is applied here.
      onEscape: () => recentImagery.clearPreview(),
    });
    // The readout mounts in its rail body through the layer panel, like the
    // weather readout.
    data.presentation.attachRecentImagery((container) =>
      createRecentImageryPanel({
        container,
        viewer,
        layer: recentImagery,
        tool: imageryBoxTool,
      }),
    );
    // The live gate's handle (scripts/qa-recent-imagery.mjs).
    const recentImageryHandle = { layer: recentImagery, tool: imageryBoxTool };
    window.__gevRecentImagery = recentImageryHandle;
    defer(() => {
      if (window.__gevRecentImagery === recentImageryHandle)
        delete window.__gevRecentImagery;
      data.presentation.attachRecentImagery(null);
      imageryBoxTool?.destroy();
    });
  }
  if (startChrome)
    defer(startChrome({ loadingScreen, styleManager, dataManager, signal }));
  // Idle render governor: flips the scene into requestRenderMode whenever
  // nothing animates per frame. Installed AFTER every module above has had
  // its chance to register pre-install holds. (perf wave 2)
  installRenderGovernor(viewer);

  // Install the explicit scope mask used by the DISPLAY controls.
  installScopeMask(viewer);
  defer(() => destroyScopeMask());

  // The follow camera recomputes the tracked target's dead-reckon position
  // every frame — tracking anything is a per-frame animation. (perf wave 2)
  const removeTrackingListener = viewer.trackedEntityChanged.addEventListener(
    () => {
      if (viewer.trackedEntity) holdContinuousRender('tracked-entity');
      else releaseContinuousRender('tracked-entity');
    },
  );

  // Hidden-state suspension (perf wave 2): when the window/tab is hidden,
  // stop the default render loop outright — a hidden canvas repaints for
  // nobody, and browser rAF throttling still lets throttled frames burn
  // GPU. Holder/data state is untouched, so return is seamless: restore
  // the loop, refresh the one DOM surface we gated, render a frame.
  const syncVisibilitySuspension = () => {
    // A panel's host may report it hidden while it is on screen; the panel
    // keeps drawing itself (see keepPanelRendering in embed.js).
    const hidden = document.hidden && !isEmbeddedInline();
    viewer.useDefaultRenderLoop = !hidden;
    cockpitCloudEffects?.setSuspended?.(hidden);
    if (!hidden) {
      data.presentation.flushVisible();
      governorRequestRender('visibility-restore');
    }
  };
  document.addEventListener('visibilitychange', syncVisibilitySuspension);
  defer(() =>
    document.removeEventListener('visibilitychange', syncVisibilitySuspension),
  );
  defer(() => {
    removeTrackingListener();
    releaseContinuousRender('tracked-entity');
  });
  // Apply the CURRENT state too — bootstrap can complete while the tab is
  // already hidden, and waiting for the next transition would leave the
  // loop burning behind a hidden tab. (perf wave 2 fix)
  syncVisibilitySuspension();

  function navigateToHash(hashInput) {
    try {
      const rawHash = String(hashInput || window.location?.hash || '').replace(/^#/, '');
      if (!rawHash) return;
      const params = new URLSearchParams(rawHash);

      const lat = parseFloat(params.get('lat'));
      const lon = parseFloat(params.get('lon'));
      const alt = parseFloat(params.get('alt')) || 1500000;
      const heading = parseFloat(params.get('heading')) || 0;
      const pitch = parseFloat(params.get('pitch')) || -60;
      const roll = parseFloat(params.get('roll')) || 0;

      if (Number.isFinite(lat) && Number.isFinite(lon)) {
        viewer.camera.flyTo({
          destination: Cesium.Cartesian3.fromDegrees(lon, lat, alt),
          orientation: {
            heading: Cesium.Math.toRadians(heading),
            pitch: Cesium.Math.toRadians(pitch),
            roll: Cesium.Math.toRadians(roll),
          },
          duration: 2.0,
        });
      }

      const style = params.get('style');
      if (style && styleManager && typeof styleManager.setStyle === 'function') {
        const targetStyle = style === 'thermal' ? 'flir' : style;
        try {
          styleManager.setStyle(targetStyle);
        } catch (e) {
          console.warn('[GodsEye] Failed to set visual style:', e);
        }
      }

      const layersParam = params.get('layers');
      if (layersParam && dataManager && typeof dataManager.setEnabled === 'function') {
        const layerList = layersParam.split(',').map((s) => s.trim()).filter(Boolean);
        for (const rawId of layerList) {
          const layerId = rawId === 'fires' ? 'firms' :
                          rawId === 'wildfires' ? 'fire-perimeters' :
                          rawId === 'aircraft' ? 'flights' :
                          rawId === 'space' ? 'launches' :
                          rawId === 'cameras' ? 'cctv' :
                          rawId === 'ships' ? 'ais-live-vessels' : rawId;
          try {
            dataManager.setEnabled(layerId, true, { origin: 'external' });
          } catch (e) {
            console.warn('[GodsEye] Failed to enable layer:', layerId, e);
          }
        }
      }

      const camId = params.get('camera') || params.get('selectedCameraId') || params.get('cctv');
      if (camId && dataManager) {
        try {
          dataManager.setEnabled('cctv', true, { origin: 'external' });
          const cctvLayer = dataManager.layers.get('cctv')?.module;
          cctvLayer?.selectCamera?.(camId);
          cctvLayer?.focusCamera?.(camId, 2.0);
        } catch (e) {
          console.warn('[GodsEye] CCTV camera selection error:', e);
        }
      }

      governorRequestRender('navigate-to-hash');
    } catch (err) {
      console.error('[GodsEye] navigateToHash error:', err);
    }
  }

  function getGlobeState() {
    try {
      const camera = viewer?.camera;
      let lat = 0, lon = 0, alt = 0, heading = 0, pitch = 0, roll = 0;
      if (camera?.positionCartographic) {
        lat = Cesium.Math.toDegrees(camera.positionCartographic.latitude);
        lon = Cesium.Math.toDegrees(camera.positionCartographic.longitude);
        alt = camera.positionCartographic.height;
        heading = Cesium.Math.toDegrees(camera.heading);
        pitch = Cesium.Math.toDegrees(camera.pitch);
        roll = Cesium.Math.toDegrees(camera.roll);
      } else if (camera?.position) {
        const carto = Cesium.Cartographic.fromCartesian(camera.position);
        if (carto) {
          lat = Cesium.Math.toDegrees(carto.latitude);
          lon = Cesium.Math.toDegrees(carto.longitude);
          alt = carto.height;
          heading = Cesium.Math.toDegrees(camera.heading);
          pitch = Cesium.Math.toDegrees(camera.pitch);
          roll = Cesium.Math.toDegrees(camera.roll);
        }
      }

      const enabledLayers = [];
      if (dataManager && typeof dataManager.getAll === 'function') {
        for (const l of dataManager.getAll()) {
          if (l.enabled) enabledLayers.push(l.id);
        }
      }

      const cctvLayer = dataManager?.layers?.get('cctv')?.module;
      const selectedCameraId = cctvLayer?.getParams?.()?.selectedCameraId || null;
      const activeStyle = styleManager?.activeStyle || 'normal';
      const entityCount = viewer?.entities?.values?.length || 0;
      const govDiag = getRenderGovernorDiagnostics();

      return {
        lat: Math.round(lat * 10000) / 10000,
        lon: Math.round(lon * 10000) / 10000,
        alt: Math.round(alt),
        heading: Math.round(heading),
        pitch: Math.round(pitch),
        roll: Math.round(roll),
        enabled_layers: enabledLayers,
        selected_camera: selectedCameraId,
        visual_style: activeStyle,
        entity_count: entityCount,
        rendering: {
          mode: govDiag.mode,
          holds: govDiag.holds,
        },
      };
    } catch (e) {
      console.warn('[GodsEye] getGlobeState error:', e);
      return { error: e?.message || String(e) };
    }
  }

  function normalizeLayerId(raw) {
    const rawId = String(raw || '').trim();
    if (!rawId) return '';
    return rawId === 'fires' ? 'firms' :
           rawId === 'wildfires' ? 'fire-perimeters' :
           rawId === 'aircraft' ? 'flights' :
           rawId === 'space' ? 'launches' :
           rawId === 'cameras' ? 'cctv' :
           rawId === 'ships' ? 'ais-live-vessels' : rawId;
  }

  let voiceCommands = null;

  function executeGodsEyeAction(action) {
    if (!action) return null;
    try {
      const act = typeof action === 'string' ? JSON.parse(action) : action;
      const traceId = act.trace_id || act.traceId || ('act_' + Date.now());

      if ((act.action || act.name || act.tool) && voiceCommands?.runner) {
        const actionName = act.name || act.action || act.tool;
        const actionArgs = act.args || act.parameters || act;
        try {
          voiceCommands.runner(actionName, actionArgs, { signal });
        } catch (runnerErr) {
          console.warn('[GodsEye] voiceCommands.runner error:', runnerErr);
        }
      }

      if (act.hash) {
        navigateToHash(act.hash);
      }
      const lat = parseFloat(act.lat);
      const lon = parseFloat(act.lon);
      if (Number.isFinite(lat) && Number.isFinite(lon)) {
        const alt = parseFloat(act.alt) || 50000;
        const heading = parseFloat(act.heading) || 0;
        const pitch = parseFloat(act.pitch) || -45;
        const roll = parseFloat(act.roll) || 0;
        const duration = parseFloat(act.duration) || 2.0;
        viewer.camera.flyTo({
          destination: Cesium.Cartesian3.fromDegrees(lon, lat, alt),
          orientation: {
            heading: Cesium.Math.toRadians(heading),
            pitch: Cesium.Math.toRadians(pitch),
            roll: Cesium.Math.toRadians(roll),
          },
          duration,
        });
      }
      if (act.style && styleManager) {
        const targetStyle = act.style === 'thermal' ? 'flir' : act.style;
        try { styleManager.setStyle(targetStyle); } catch (_) {}
      }
      if (act.clear_all && dataManager && typeof dataManager.getAll === 'function') {
        for (const l of dataManager.getAll()) {
          if (l.enabled) {
            try { dataManager.setEnabled(l.id, false, { origin: 'external' }); } catch (_) {}
          }
        }
      }
      if (act.exclusive && dataManager && typeof dataManager.getAll === 'function') {
        const keepList = new Set();
        if (act.layers) {
          const rawList = Array.isArray(act.layers) ? act.layers : String(act.layers).split(',');
          for (const raw of rawList) keepList.add(normalizeLayerId(raw));
        }
        if (act.enable_layers) {
          const rawList = Array.isArray(act.enable_layers) ? act.enable_layers : String(act.enable_layers).split(',');
          for (const raw of rawList) keepList.add(normalizeLayerId(raw));
        }
        for (const l of dataManager.getAll()) {
          if (l.enabled && !keepList.has(l.id)) {
            try { dataManager.setEnabled(l.id, false, { origin: 'external' }); } catch (_) {}
          }
        }
      }
      if (act.layers && dataManager) {
        const rawList = Array.isArray(act.layers) ? act.layers : String(act.layers).split(',');
        for (const raw of rawList) {
          const normalized = normalizeLayerId(raw);
          if (!normalized) continue;
          try {
            dataManager.setEnabled(normalized, act.enabled !== false, { origin: 'external' });
          } catch (_) {}
        }
      }
      if (act.enable_layers && dataManager) {
        const rawList = Array.isArray(act.enable_layers) ? act.enable_layers : String(act.enable_layers).split(',');
        for (const raw of rawList) {
          const normalized = normalizeLayerId(raw);
          if (normalized) {
            try { dataManager.setEnabled(normalized, true, { origin: 'external' }); } catch (_) {}
          }
        }
      }
      if (act.disable_layers && dataManager) {
        const rawList = Array.isArray(act.disable_layers) ? act.disable_layers : String(act.disable_layers).split(',');
        for (const raw of rawList) {
          const normalized = normalizeLayerId(raw);
          if (normalized) {
            try { dataManager.setEnabled(normalized, false, { origin: 'external' }); } catch (_) {}
          }
        }
      }
      const camId = act.cameraId || act.selectedCameraId;
      if (camId && dataManager) {
        try {
          dataManager.setEnabled('cctv', true, { origin: 'external' });
          const cctvLayer = dataManager.layers.get('cctv')?.module;
          cctvLayer?.selectCamera?.(camId);
          cctvLayer?.focusCamera?.(camId, 2.0);
        } catch (_) {}
      }
      governorRequestRender('execute-godseye-action');

      if (typeof window.__showJarvisResultCard === 'function') {
        const lines = [];
        if (Number.isFinite(lat) && Number.isFinite(lon)) lines.push(`Coordinates: ${lat.toFixed(4)}, ${lon.toFixed(4)}`);
        if (act.style) lines.push(`Style: ${act.style}`);
        if (act.layers || act.enable_layers) lines.push(`Layers active: ${act.layers || act.enable_layers}`);
        if (camId) lines.push(`CCTV Camera: ${camId}`);
        if (act.summary) lines.push(act.summary);
        if (lines.length > 0) {
          window.__showJarvisResultCard({
            title: act.title || 'Jarvis Action Executed',
            lines,
            phase: 'EXECUTED',
          });
        }
      }

      const receipt = {
        trace_id: traceId,
        status: 'succeeded',
        state: getGlobeState(),
        timestamp: Date.now(),
      };

      try {
        if (typeof window.JarvisVoiceBridge?.onActionAcknowledged === 'function') {
          window.JarvisVoiceBridge.onActionAcknowledged(traceId, JSON.stringify(receipt));
        }
      } catch (bridgeErr) {
        console.warn('[GodsEye] VoiceBridge acknowledgement error:', bridgeErr);
      }

      return receipt;
    } catch (err) {
      console.error('[GodsEye] executeGodsEyeAction error:', err);
      const failReceipt = {
        trace_id: action?.trace_id || action?.traceId || 'unknown',
        status: 'failed',
        error: err?.message || String(err),
        timestamp: Date.now(),
      };
      try {
        if (typeof window.JarvisVoiceBridge?.onActionAcknowledged === 'function') {
          window.JarvisVoiceBridge.onActionAcknowledged(failReceipt.trace_id, JSON.stringify(failReceipt));
        }
      } catch (_) {}
      return failReceipt;
    }
  }

  const onHashChange = () => navigateToHash();
  window.addEventListener('hashchange', onHashChange);
  defer(() => window.removeEventListener('hashchange', onHashChange));

  if (window.location?.hash) {
    setTimeout(() => navigateToHash(), 800);
  }

  window.__godsEyeGetState = getGlobeState;
  window.__executeGodsEyeAction = executeGodsEyeAction;
  if (Array.isArray(window.__pendingGodsEyeActions) && window.__pendingGodsEyeActions.length > 0) {
    const pending = window.__pendingGodsEyeActions.splice(0, window.__pendingGodsEyeActions.length);
    for (const act of pending) {
      executeGodsEyeAction(act);
    }
  }

  window.__godsEyeView = {
    viewer,
    styleManager,
    tileset,
    dataManager,
    sceneDirector,
    mapStackController,
    annotations,
    weatherEffects,
    cockpitCloudEffects,
    getRenderGovernorDiagnostics,
    surfaceServices: operations.surface,
    requestRender: governorRequestRender,
    navigateToHash,
    executeAction: executeGodsEyeAction,
    getState: getGlobeState,
  };
  const debug = window.__godsEyeView;
  defer(() => {
    if (window.__godsEyeView === debug) delete window.__godsEyeView;
    if (window.__executeGodsEyeAction === executeGodsEyeAction) delete window.__executeGodsEyeAction;
    if (window.__godsEyeGetState === getGlobeState) delete window.__godsEyeGetState;
  });
  voiceCommands = initGevVoiceCommands({
    ...voice,
    floorServices: operations.surface.groundFloor,
    annotationResolver: operations.annotationResolver,
    searchNavigation: operations.searchAndFlyTo,
    signal,
    placeSearch,
    viewer,
    styleManager,
    dataManager,
    sceneDirector,
    annotations,
  });
  defer(() => {
    voiceCommands.stop({ removeUi: true });
    if (window.__gevVoiceCommands === voiceCommands)
      delete window.__gevVoiceCommands;
  });
  debug.voiceCommands = voiceCommands;
  defer(
    installViews({
      shell: styleManager,
      viewer,
      dataManager,
      run: (name, args) => voiceCommands.runner(name, args, { signal }),
      signal,
    }),
  );
  return { sceneDirector, annotations, voiceCommands };
}
