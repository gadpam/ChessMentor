// Точка входа: цикл кадров, управление с клавиатуры, автосохранение.

import {
  createGame, update, serialize, nearestInteractable, openInteractable, closeModal,
  applyAction, buyItem, startGame, startNextDay, deviceById, PHASE,
} from './game.js';
import { drawWorld } from './render.js';
import { updateHUD, updateTickets, renderLog, renderHint, renderModal, modalSignature } from './ui.js';

const SAVE_KEY = 'sysadmin-sim-save-v1';
const FLASH_MS = 2500;

const canvas = document.getElementById('game');
const ctx = canvas.getContext('2d');

const ui = { tracked: null, flash: '', flashTimer: 0 };

function flash(msg) {
  ui.flash = msg;
  clearTimeout(ui.flashTimer);
  ui.flashTimer = setTimeout(() => { ui.flash = ''; }, FLASH_MS);
}

function loadOrCreate() {
  try {
    const raw = localStorage.getItem(SAVE_KEY);
    if (raw) return createGame({ save: JSON.parse(raw) });
  } catch (e) {
    console.warn('Сохранение повреждено, начинаем новую игру', e);
  }
  return createGame();
}

let state = loadOrCreate();

const api = {
  onStart() { startGame(state); },
  onClose() { closeModal(state); },
  onNextDay() { startNextDay(state); },
  onTrack(deviceId) { ui.tracked = deviceId; },
  onAction(deviceId, actionId) {
    const r = applyAction(state, deviceId, actionId);
    if (r.ok) {
      closeModal(state);
      ui.flash = '';
    } else {
      flash(r.msg);
    }
  },
  onBuy(itemId) {
    const r = buyItem(state, itemId);
    flash(r.msg);
  },
  onRestart() {
    localStorage.removeItem(SAVE_KEY);
    state = createGame();
    ui.tracked = null;
    ui.flash = '';
  },
};

// ---------- ввод ----------

const MOVE_KEYS = {
  up: ['KeyW', 'ArrowUp'],
  down: ['KeyS', 'ArrowDown'],
  left: ['KeyA', 'ArrowLeft'],
  right: ['KeyD', 'ArrowRight'],
};
const held = new Set();

function isHeld(group) {
  return MOVE_KEYS[group].some((c) => held.has(c));
}

function readInput() {
  let dx = (isHeld('right') ? 1 : 0) - (isHeld('left') ? 1 : 0);
  let dy = (isHeld('down') ? 1 : 0) - (isHeld('up') ? 1 : 0);
  if (dx && dy) {
    const len = Math.SQRT2;
    dx /= len;
    dy /= len;
  }
  return { dx, dy };
}

window.addEventListener('keydown', (e) => {
  const code = e.code;
  if (['ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight', 'Space'].includes(code)) e.preventDefault();

  if (state.phase === PHASE.INTRO) {
    if (['Enter', 'Space', 'KeyE'].includes(code) && !e.repeat) startGame(state);
    return;
  }
  if (state.phase !== PHASE.PLAYING) return;

  if (code === 'Escape') {
    closeModal(state);
    return;
  }
  if (state.modal) return;

  if (['KeyE', 'Enter', 'Space'].includes(code)) {
    if (!e.repeat) openInteractable(state, nearestInteractable(state));
    return;
  }
  for (const group of Object.keys(MOVE_KEYS)) {
    if (MOVE_KEYS[group].includes(code)) held.add(code);
  }
});

window.addEventListener('keyup', (e) => held.delete(e.code));
window.addEventListener('blur', () => held.clear());

// ---------- цикл ----------

let last = performance.now();
let domTimer = 0;
let lastSig = '';

function frame(now) {
  const dt = Math.min(0.25, (now - last) / 1000);
  last = now;

  update(state, dt, readInput());

  const active = state.phase === PHASE.PLAYING && !state.modal;
  const nearest = active ? nearestInteractable(state) : null;

  // Если отслеживаемая заявка закрыта — снимаем подсветку.
  if (ui.tracked) {
    const d = deviceById(state, ui.tracked);
    if (!d || !d.broken) ui.tracked = null;
  }

  drawWorld(ctx, state, now / 1000, { nearest, tracked: ui.tracked });

  domTimer += dt;
  if (domTimer >= 0.1) {
    domTimer = 0;
    updateHUD(state);
    updateTickets(state, ui, api.onTrack);
    renderLog(state);
    renderHint(state, nearest);
    const sig = modalSignature(state, ui);
    if (sig !== lastSig) {
      lastSig = sig;
      renderModal(state, ui, api);
    }
  }

  requestAnimationFrame(frame);
}

// Автосохранение во время смены.
setInterval(() => {
  try {
    if (state.phase === PHASE.PLAYING) {
      localStorage.setItem(SAVE_KEY, JSON.stringify(serialize(state)));
    } else if (state.phase === PHASE.GAMEOVER) {
      localStorage.removeItem(SAVE_KEY);
    }
  } catch (e) {
    console.warn('Не удалось сохранить игру', e);
  }
}, 2000);

document.getElementById('btn-new').addEventListener('click', () => {
  if (confirm('Начать новую игру? Текущий прогресс будет потерян.')) api.onRestart();
});

requestAnimationFrame((t) => {
  last = t;
  frame(t);
});
