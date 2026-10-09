// Отрисовка мира на Canvas. Не меняет состояние игры.

import { TILE } from './data.js';
import { tileAt } from './game.js';

const C = {
  bg: '#111827',
  floorA: '#e9e4d8',
  floorB: '#e0dacb',
  serverA: '#cdd9e6',
  serverB: '#c4d1e0',
  storageA: '#eadcc4',
  storageB: '#e2d3b6',
  wall: '#4b5563',
  wallTop: '#6b7280',
  desk: '#8b5e3c',
  deskTop: '#a77550',
  monitorFrame: '#111827',
  screenOk: '#3b82f6',
  screenBroken: '#ef4444',
  screenBrokenDark: '#7f1d1d',
  screenVirus: '#a855f7',
  screenVirusDark: '#4c1d95',
  printerBody: '#9ca3af',
  printerPaper: '#f8fafc',
  rack: '#1f2937',
  ledOk: '#22c55e',
  ledBad: '#ef4444',
  shop: '#166534',
  shopDoor: '#15803d',
  player: '#2563eb',
  skin: '#fcd9b6',
  hair: '#4b2e1e',
  ticket: '#facc15',
};

function floorColor(x, y) {
  const checker = (x + y) % 2 === 0;
  if (x >= 17 && y <= 6) return checker ? C.serverA : C.serverB;
  if (x >= 17) return checker ? C.storageA : C.storageB;
  return checker ? C.floorA : C.floorB;
}

export function drawWorld(ctx, state, time, opts = {}) {
  const { width, height } = state;
  ctx.fillStyle = C.bg;
  ctx.fillRect(0, 0, ctx.canvas.width, ctx.canvas.height);

  for (let y = 0; y < height; y++) {
    for (let x = 0; x < width; x++) {
      const px = x * TILE;
      const py = y * TILE;
      if (tileAt(state, x, y) === '#') {
        ctx.fillStyle = C.wall;
        ctx.fillRect(px, py, TILE, TILE);
        if (tileAt(state, x, y - 1) !== '#') {
          ctx.fillStyle = C.wallTop;
          ctx.fillRect(px, py, TILE, 4);
        }
      } else {
        ctx.fillStyle = floorColor(x, y);
        ctx.fillRect(px, py, TILE, TILE);
      }
    }
  }

  for (const d of state.devices) drawDevice(ctx, d, time);
  if (state.shop) drawShop(ctx, state.shop);

  if (opts.tracked) {
    const d = state.devices.find((x) => x.id === opts.tracked);
    if (d && d.broken) drawRing(ctx, d, time, '#38bdf8');
  }
  if (opts.nearest) {
    drawRing(ctx, opts.nearest, time, C.ticket);
    drawKeyChip(ctx, opts.nearest);
  }

  drawPlayer(ctx, state.player, time);
}

function drawDevice(ctx, d, time) {
  if (d.type === 'pc') drawPC(ctx, d, time);
  else if (d.type === 'printer') drawPrinter(ctx, d, time);
  else if (d.type === 'server') drawServer(ctx, d, time);

  const px = d.x * TILE;
  const py = d.y * TILE;
  if (d.ticketId) {
    // Жёлтый «пузырь» над устройством — есть открытая заявка.
    const r = 6 + Math.sin(time * 5) * 1;
    ctx.beginPath();
    ctx.fillStyle = C.ticket;
    ctx.arc(px + TILE - 7, py + 7, r, 0, Math.PI * 2);
    ctx.fill();
    ctx.fillStyle = '#111827';
    ctx.font = 'bold 10px system-ui, sans-serif';
    ctx.textAlign = 'center';
    ctx.textBaseline = 'middle';
    ctx.fillText('!', px + TILE - 7, py + 7.5);
  } else if (d.broken) {
    // Сломано, но заявки нет — красная точка.
    ctx.beginPath();
    ctx.fillStyle = C.screenBroken;
    ctx.arc(px + 6, py + 6, 3, 0, Math.PI * 2);
    ctx.fill();
  }
}

function drawPC(ctx, d, time) {
  const px = d.x * TILE;
  const py = d.y * TILE;
  // стол
  ctx.fillStyle = C.desk;
  ctx.fillRect(px + 2, py + 17, TILE - 4, 13);
  ctx.fillStyle = C.deskTop;
  ctx.fillRect(px + 2, py + 17, TILE - 4, 3);
  // системник
  ctx.fillStyle = '#374151';
  ctx.fillRect(px + TILE - 10, py + 13, 6, 10);
  // монитор
  ctx.fillStyle = C.monitorFrame;
  ctx.fillRect(px + 6, py + 3, TILE - 12, 14);
  ctx.fillStyle = monitorColor(d, time);
  ctx.fillRect(px + 8, py + 5, TILE - 16, 10);
  // подставка
  ctx.fillStyle = '#374151';
  ctx.fillRect(px + 14, py + 17, 4, 2);
}

function monitorColor(d, time) {
  if (!d.broken) return C.screenOk;
  const blink = Math.floor(time * 2) % 2 === 0;
  if (d.broken.fix.includes('av') || d.broken.title === 'Вирус') {
    return blink ? C.screenVirus : C.screenVirusDark;
  }
  return blink ? C.screenBroken : C.screenBrokenDark;
}

function drawPrinter(ctx, d, time) {
  const px = d.x * TILE;
  const py = d.y * TILE;
  ctx.fillStyle = C.printerBody;
  ctx.fillRect(px + 4, py + 12, TILE - 8, 14);
  ctx.fillStyle = C.printerPaper;
  ctx.fillRect(px + 9, py + 5, TILE - 18, 9);
  ctx.fillStyle = '#4b5563';
  ctx.fillRect(px + 4, py + 22, TILE - 8, 4);
  const led = d.broken ? (Math.floor(time * 3) % 2 ? C.ledBad : '#7f1d1d') : C.ledOk;
  ctx.fillStyle = led;
  ctx.fillRect(px + TILE - 9, py + 15, 3, 3);
}

function drawServer(ctx, d, time) {
  const px = d.x * TILE;
  const py = d.y * TILE;
  ctx.fillStyle = C.rack;
  ctx.fillRect(px + 6, py + 1, TILE - 12, TILE - 2);
  for (let i = 0; i < 4; i++) {
    const y = py + 4 + i * 7;
    ctx.fillStyle = '#374151';
    ctx.fillRect(px + 8, y, TILE - 16, 5);
    const blinkOn = Math.floor(time * 4 + i + d.id) % 3 !== 0;
    ctx.fillStyle = d.broken ? (Math.floor(time * 3 + i) % 2 ? C.ledBad : '#450a0a') : (blinkOn ? C.ledOk : '#14532d');
    ctx.fillRect(px + 10, y + 2, 3, 2);
    ctx.fillStyle = d.broken ? C.ledBad : '#60a5fa';
    ctx.fillRect(px + 16, y + 2, 3, 2);
  }
}

function drawShop(ctx, s) {
  const px = s.x * TILE;
  const py = s.y * TILE;
  ctx.fillStyle = C.shop;
  ctx.fillRect(px + 3, py + 1, TILE - 6, TILE - 2);
  ctx.fillStyle = C.shopDoor;
  ctx.fillRect(px + 6, py + 4, TILE - 12, TILE - 8);
  ctx.fillStyle = '#fde047';
  ctx.font = 'bold 16px system-ui, sans-serif';
  ctx.textAlign = 'center';
  ctx.textBaseline = 'middle';
  ctx.fillText('$', px + TILE / 2, py + TILE / 2 + 1);
}

function drawRing(ctx, o, time, color) {
  const cx = o.x * TILE + TILE / 2;
  const cy = o.y * TILE + TILE / 2;
  const r = 19 + Math.sin(time * 4) * 2;
  ctx.beginPath();
  ctx.strokeStyle = color;
  ctx.lineWidth = 2;
  ctx.arc(cx, cy, r, 0, Math.PI * 2);
  ctx.stroke();
}

function drawKeyChip(ctx, o) {
  const cx = o.x * TILE + TILE / 2;
  const top = o.y * TILE - 6;
  ctx.fillStyle = '#111827';
  ctx.fillRect(cx - 9, top - 14, 18, 14);
  ctx.fillStyle = C.ticket;
  ctx.font = 'bold 11px system-ui, sans-serif';
  ctx.textAlign = 'center';
  ctx.textBaseline = 'middle';
  ctx.fillText('E', cx, top - 7);
}

function drawPlayer(ctx, p, time) {
  const cx = p.x * TILE;
  const cy = p.y * TILE;
  const bob = Math.sin(time * 6) * 0.5;
  // тень
  ctx.fillStyle = 'rgba(0,0,0,0.18)';
  ctx.beginPath();
  ctx.ellipse(cx, cy + 11, 8, 3, 0, 0, Math.PI * 2);
  ctx.fill();
  // тело
  ctx.fillStyle = C.player;
  ctx.beginPath();
  ctx.arc(cx, cy + 4 + bob, 8, 0, Math.PI * 2);
  ctx.fill();
  // голова
  ctx.fillStyle = C.skin;
  ctx.beginPath();
  ctx.arc(cx, cy - 6 + bob, 6, 0, Math.PI * 2);
  ctx.fill();
  ctx.fillStyle = C.hair;
  ctx.beginPath();
  ctx.arc(cx, cy - 8 + bob, 6, Math.PI, 0);
  ctx.fill();
}
