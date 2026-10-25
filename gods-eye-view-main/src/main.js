import { createStandaloneApplication } from './standalone/application.js';
import { describeError } from './standalone/errors.js';

const resolvedGoogleApiKey =
  (typeof window !== 'undefined' && window.__GOOGLE_MAPS_API_KEY__) ||
  import.meta.env.GOOGLE_MAPS_API_KEY ||
  '';

const resolvedCesiumToken =
  (typeof window !== 'undefined' &&
    (window.__CESIUM_ION_TOKEN__ ||
      (window.Cesium && window.Cesium.Ion && window.Cesium.Ion.defaultAccessToken))) ||
  import.meta.env.CESIUM_ION_TOKEN ||
  '';

const application = createStandaloneApplication({
  googleApiKey: resolvedGoogleApiKey,
  cesiumToken: resolvedCesiumToken,
  allowQaRegistration: import.meta.env.DEV,
});

application.start().catch((error) => {
  console.error("God's Eye View initialization failed:", error);
  const loaderStatus = document.querySelector('#loading-screen .loader-status');
  loaderStatus.textContent = `Error: ${describeError(error)}`;
  loaderStatus.style.color = '#ff4444';
});

export { application };
