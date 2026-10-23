import { test } from 'node:test';
import assert from 'node:assert/strict';
import { projectPoint } from '../../data/cctvFootprint.js';
import { frustumVolumeGeometryData, cameraHue, viewshedColors } from '../../data/cctvViewshed.js';

test('projectPoint handles extreme distances and NaN safely without throwing', () => {
  const p1 = projectPoint(0, 0, 0, 1000);
  assert.ok(Number.isFinite(p1.lat));
  assert.ok(Number.isFinite(p1.lon));

  // Extreme distance that could exceed 1.0 in Math.asin argument
  const p2 = projectPoint(89.9, 0, 0, 50000000);
  assert.ok(Number.isFinite(p2.lat));
  assert.ok(Number.isFinite(p2.lon));
  assert.ok(p2.lat >= -90 && p2.lat <= 90);

  // NaN inputs
  const p3 = projectPoint(NaN, NaN, NaN, NaN);
  assert.ok(Number.isFinite(p3.lat));
  assert.ok(Number.isFinite(p3.lon));
});

test('frustumVolumeGeometryData never outputs NaN even with malformed or missing vertices', () => {
  const malformed = {
    mount: { x: NaN, y: 10, z: undefined },
    tl: { x: Infinity, y: -Infinity, z: 0 },
    tr: null,
    br: { x: 5, y: 5 },
    bl: undefined,
  };

  const { positions, indices } = frustumVolumeGeometryData(malformed);
  assert.equal(positions.length, 15);
  assert.equal(indices.length, 18);

  for (let i = 0; i < positions.length; i++) {
    assert.ok(Number.isFinite(positions[i]), `Position at index ${i} must be finite: ${positions[i]}`);
  }
});

test('cameraHue and viewshedColors safely handle non-numeric or infinite inputs', () => {
  assert.ok(Number.isFinite(cameraHue(NaN)));
  assert.ok(Number.isFinite(cameraHue(-5)));
  assert.ok(Number.isFinite(cameraHue(Infinity)));

  const colors = viewshedColors(NaN);
  assert.ok(colors.fill);
  assert.ok(colors.fillActive);
  assert.ok(colors.line);
  assert.ok(colors.lineActive);
});
