// Игровая логика без DOM и без canvas — её можно тестировать в Node.

import {
  MAP, SPAWN, START, MAX_REP, DEBT_LIMIT, DAY_LENGTH, SOLID_CHARS, DEVICE_CHARS,
  DEVICE_INFO, ACTIONS, FAULTS, ITEMS, CALLERS, EXPENSES,
} from './data.js';

export const PHASE = {
  INTRO: 'intro',       // стартовый экран
  PLAYING: 'playing',   // рабочая смена
  SUMMARY: 'summary',   // итоги дня и магазин
  GAMEOVER: 'gameover', // увольнение или банкротство
};

const PLAYER_RADIUS = 0.3;   // полуширина хитбокса игрока, в клетках
const PLAYER_SPEED = 3.2;    // клеток в секунду
const INTERACT_RANGE = 1.1;  // дистанция до объекта, на которой работает E

export const clamp = (v, lo = 0, hi = MAX_REP) => Math.max(lo, Math.min(hi, v));

export function pick(rng, arr) {
  return arr[Math.floor(rng() * arr.length)];
}

export function weightedPick(rng, arr) {
  const total = arr.reduce((s, x) => s + x.weight, 0);
  let r = rng() * total;
  for (const x of arr) {
    r -= x.weight;
    if (r < 0) return x;
  }
  return arr[arr.length - 1];
}

export function shuffle(rng, arr) {
  const a = [...arr];
  for (let i = a.length - 1; i > 0; i--) {
    const j = Math.floor(rng() * (i + 1));
    [a[i], a[j]] = [a[j], a[i]];
  }
  return a;
}

export function parseMap(rows = MAP) {
  const height = rows.length;
  const width = rows[0].length;
  rows.forEach((row, y) => {
    if (row.length !== width) {
      throw new Error(`Строка карты ${y} длиной ${row.length}, ожидалось ${width}`);
    }
  });

  const counters = {};
  const devices = [];
  let shop = null;
  for (let y = 0; y < height; y++) {
    for (let x = 0; x < width; x++) {
      const ch = rows[y][x];
      const type = DEVICE_CHARS[ch];
      if (type) {
        counters[type] = (counters[type] || 0) + 1;
        devices.push({
          id: devices.length + 1,
          kind: 'device',
          type,
          label: `${DEVICE_INFO[type].label}-${counters[type]}`,
          x,
          y,
          ticketId: null, // id открытой заявки или null
          broken: null,   // { title, text, fix, actions } или null
        });
      } else if (ch === 'H') {
        shop = { kind: 'shop', x, y };
      }
    }
  }
  return { width, height, rows, devices, shop };
}

export function log(state, text) {
  state.log.unshift({ text, day: state.day });
  if (state.log.length > 60) state.log.length = 60;
}

export function createGame({ rng = Math.random, save = null } = {}) {
  const map = parseMap();
  const state = {
    rng,
    width: map.width,
    height: map.height,
    rows: map.rows,
    devices: map.devices,
    shop: map.shop,
    player: { x: SPAWN.x + 0.5, y: SPAWN.y + 0.5 },
    phase: PHASE.INTRO,
    modal: null,
    day: 1,
    money: START.money,
    rep: START.rep,
    dayTimeLeft: DAY_LENGTH,
    tickets: [],
    ticketSeq: 1,
    nextCallIn: 3,
    inventory: { antivirus: false, shoes: false, psu: 0, toner: 0, hdd: 0 },
    today: { fixed: 0, expired: 0, earned: 0 },
    totals: { fixed: 0, expired: 0, earned: 0, wrong: 0 },
    summary: null,
    gameOver: null,
    log: [],
  };
  if (save) {
    applySave(state, save);
  } else {
    state.modal = { type: 'intro' };
    log(state, 'Добро пожаловать в офис. Звонки сотрудников будут приходить в очередь.');
  }
  return state;
}

export function startGame(state) {
  if (state.phase !== PHASE.INTRO) return;
  state.phase = PHASE.PLAYING;
  state.modal = null;
  log(state, `Смена ${state.day} начата.`);
}

// ---------- сохранение ----------

export function serialize(state) {
  return {
    v: 1,
    day: state.day,
    money: state.money,
    rep: state.rep,
    dayTimeLeft: state.dayTimeLeft,
    ticketSeq: state.ticketSeq,
    nextCallIn: state.nextCallIn,
    inventory: state.inventory,
    totals: state.totals,
    tickets: state.tickets,
    devices: state.devices.map((d) => ({ id: d.id, broken: d.broken, ticketId: d.ticketId })),
  };
}

function applySave(state, s) {
  state.day = s.day;
  state.money = s.money;
  state.rep = s.rep;
  state.dayTimeLeft = s.dayTimeLeft;
  state.ticketSeq = s.ticketSeq;
  state.nextCallIn = s.nextCallIn;
  state.inventory = { ...state.inventory, ...s.inventory };
  state.totals = { ...state.totals, ...s.totals };
  state.tickets = s.tickets.map((t) => ({ ...t }));
  const saved = new Map(s.devices.map((d) => [d.id, d]));
  for (const dev of state.devices) {
    const d = saved.get(dev.id);
    if (d) {
      dev.broken = d.broken;
      dev.ticketId = d.ticketId;
    }
  }
  state.phase = PHASE.PLAYING;
  state.modal = null;
  log(state, `Смена ${state.day} продолжается.`);
}

// ---------- карта и движение ----------

export function tileAt(state, x, y) {
  if (x < 0 || y < 0 || x >= state.width || y >= state.height) return '#';
  return state.rows[y][x];
}

function isSolidAt(state, x, y) {
  return SOLID_CHARS.includes(tileAt(state, Math.floor(x), Math.floor(y)));
}

function blocked(state, x, y) {
  const r = PLAYER_RADIUS;
  return (
    isSolidAt(state, x - r, y - r) ||
    isSolidAt(state, x + r, y - r) ||
    isSolidAt(state, x - r, y + r) ||
    isSolidAt(state, x + r, y + r)
  );
}

export function movePlayer(state, dx, dy, dt) {
  const p = state.player;
  const speed = PLAYER_SPEED * (state.inventory.shoes ? ITEMS.shoes.speed : 1) * dt;
  // Оси двигаем раздельно, чтобы игрок скользил вдоль стены.
  const nx = p.x + dx * speed;
  if (!blocked(state, nx, p.y)) p.x = nx;
  const ny = p.y + dy * speed;
  if (!blocked(state, p.x, ny)) p.y = ny;
}

export function interactables(state) {
  return state.shop ? [...state.devices, state.shop] : [...state.devices];
}

export function nearestInteractable(state) {
  let best = null;
  let bestD = INTERACT_RANGE;
  for (const o of interactables(state)) {
    const d = Math.hypot(o.x + 0.5 - state.player.x, o.y + 0.5 - state.player.y);
    if (d <= bestD) {
      best = o;
      bestD = d;
    }
  }
  return best;
}

export function deviceById(state, id) {
  return state.devices.find((d) => d.id === id) || null;
}

export function deviceLabel(state, obj) {
  return obj.kind === 'shop' ? 'Склад' : obj.label;
}

export function openInteractable(state, obj) {
  if (!obj || state.phase !== PHASE.PLAYING) return;
  state.modal = obj.kind === 'shop'
    ? { type: 'shop' }
    : { type: 'device', deviceId: obj.id };
}

export function closeModal(state) {
  if (state.phase === PHASE.PLAYING && state.modal) state.modal = null;
}

// ---------- заявки ----------

export function maxOpenTickets(day) {
  return Math.min(2 + Math.ceil(day / 2), 5);
}

function scheduleCall(state) {
  const base = Math.max(4, 12 - (state.day - 1) * 0.8);
  state.nextCallIn = base * (0.7 + state.rng() * 0.6);
}

export function spawnTicket(state) {
  if (state.tickets.length >= maxOpenTickets(state.day)) return null;

  const faults = FAULTS.filter((f) => f.minDay <= state.day);
  const candidates = faults.filter((f) =>
    state.devices.some((d) => d.type === f.device && !d.broken));
  if (!candidates.length) return null;

  const fault = weightedPick(state.rng, candidates);
  const dev = pick(state.rng, state.devices.filter((d) => d.type === fault.device && !d.broken));
  const variant = pick(state.rng, fault.variants);

  dev.broken = {
    title: fault.title,
    text: variant.text,
    fix: variant.fix,
    actions: shuffle(state.rng, fault.actions),
  };

  const info = DEVICE_INFO[dev.type];
  const maxTime = Math.max(45, info.maxTime - (state.day - 1) * 3);
  const ticket = {
    id: state.ticketSeq++,
    deviceId: dev.id,
    caller: pick(state.rng, CALLERS),
    timeLeft: maxTime,
    maxTime,
    reward: info.reward,
  };
  dev.ticketId = ticket.id;
  state.tickets.push(ticket);
  log(state, `📞 ${ticket.caller}: «${dev.broken.title}» — ${dev.label}`);
  return ticket;
}

function expireTicket(state, ticket) {
  const dev = deviceById(state, ticket.deviceId);
  if (dev) dev.ticketId = null; // устройство остаётся сломанным, но без заявки
  state.tickets = state.tickets.filter((t) => t !== ticket);
  state.rep = clamp(state.rep - 6);
  state.today.expired++;
  state.totals.expired++;
  log(state, `⏰ ${ticket.caller} ждал слишком долго. ${dev ? dev.label : 'Устройство'} не починено. −6 репутации`);
  checkGameOver(state);
}

// Применить действие диагностики к устройству.
// Возвращает { ok, msg?, blocked?, reward?, fast? }.
export function applyAction(state, deviceId, actionId) {
  const dev = deviceById(state, deviceId);
  if (!dev || !dev.broken) return { ok: false, msg: 'Устройство работает нормально.' };

  const act = ACTIONS[actionId];
  if (!act) throw new Error(`Неизвестное действие: ${actionId}`);

  if (act.needs && !hasStock(state, act.needs)) {
    return {
      ok: false,
      blocked: true,
      msg: `Нужно: ${ITEMS[act.needs].name}. Купите на складе (H).`,
    };
  }

  const ticket = dev.ticketId ? state.tickets.find((t) => t.id === dev.ticketId) : null;
  if (ticket) ticket.timeLeft -= act.time;

  if (dev.broken.fix.includes(actionId)) {
    if (act.consumes) state.inventory[act.consumes] -= 1;
    dev.broken = null;
    dev.ticketId = null;

    let reward;
    let fast = false;
    if (ticket) {
      state.tickets = state.tickets.filter((t) => t !== ticket);
      fast = ticket.timeLeft > ticket.maxTime * 0.5;
      reward = Math.round(ticket.reward * (fast ? 1.5 : 1));
      state.rep = clamp(state.rep + (fast ? 3 : 2));
    } else {
      // Устройство сломалось без заявки (заявку закрыли по таймеру) — платят меньше.
      reward = Math.round(DEVICE_INFO[dev.type].reward * 0.5);
    }
    state.money += reward;
    state.today.fixed++;
    state.totals.fixed++;
    state.today.earned += reward;
    state.totals.earned += reward;
    log(state, `✔ ${dev.label}: ${act.label}. +${reward} ₽${fast ? ' (быстро!)' : ''}`);
    return { ok: true, reward, fast };
  }

  state.rep = clamp(state.rep - 1);
  state.totals.wrong++;
  log(state, `✖ ${dev.label}: «${act.label}» не помогло (−${act.time} с, −1 репутация)`);
  checkGameOver(state);
  return { ok: false, msg: 'Не помогло. Попробуйте другое действие.' };
}

// ---------- склад ----------

export function hasStock(state, item) {
  const v = state.inventory[item];
  return typeof v === 'boolean' ? v : v > 0;
}

export function buyItem(state, itemId) {
  const item = ITEMS[itemId];
  if (!item) throw new Error(`Нет товара: ${itemId}`);
  if (item.once && state.inventory[itemId]) return { ok: false, msg: 'Уже куплено.' };
  if (state.money < item.price) return { ok: false, msg: 'Недостаточно денег.' };
  state.money -= item.price;
  if (item.once) state.inventory[itemId] = true;
  else state.inventory[itemId] += 1;
  log(state, `🛒 Куплено: ${item.name} (−${item.price} ₽)`);
  return { ok: true, msg: `Куплено: ${item.name}` };
}

// ---------- время, итоги дня, конец игры ----------

const MAX_STEP = 0.1;

// Большой dt (например, после сворачивания вкладки) дробится на мелкие шаги,
// чтобы заявки и таймеры вели себя одинаково независимо от частоты кадров.
export function update(state, dt, input = { dx: 0, dy: 0 }) {
  const steps = Math.max(1, Math.ceil(dt / MAX_STEP));
  for (let i = 0; i < steps; i++) {
    if (state.phase !== PHASE.PLAYING || state.modal) return;
    step(state, dt / steps, input);
  }
}

function step(state, dt, input) {
  if (input.dx || input.dy) movePlayer(state, input.dx, input.dy, dt);

  state.dayTimeLeft -= dt;
  state.nextCallIn -= dt;
  if (state.nextCallIn <= 0) {
    spawnTicket(state);
    scheduleCall(state);
  }

  for (const t of [...state.tickets]) {
    t.timeLeft -= dt;
    if (t.timeLeft <= 0) expireTicket(state, t);
    if (state.phase !== PHASE.PLAYING) return;
  }

  if (state.dayTimeLeft <= 0) endDay(state);
}

export function endDay(state) {
  const unresolved = state.tickets.length;
  state.rep = clamp(state.rep - 2 * unresolved);
  const expenses = EXPENSES(state.day);
  state.money -= expenses;
  state.summary = {
    day: state.day,
    fixed: state.today.fixed,
    expired: state.today.expired,
    earned: state.today.earned,
    expenses,
    unresolved,
    money: state.money,
    rep: state.rep,
  };
  state.phase = PHASE.SUMMARY;
  state.modal = null;
  log(state, `Конец дня ${state.day}. Расходы: −${expenses} ₽`);
  checkGameOver(state);
}

export function startNextDay(state) {
  if (state.phase !== PHASE.SUMMARY) return;
  state.day++;
  state.dayTimeLeft = DAY_LENGTH;
  state.today = { fixed: 0, expired: 0, earned: 0 };
  // Незакрытые заявки переносятся на следующий день, таймер сбрасывается.
  for (const t of state.tickets) t.timeLeft = t.maxTime;
  state.summary = null;
  state.phase = PHASE.PLAYING;
  state.modal = null;
  scheduleCall(state);
  log(state, `Смена ${state.day} начата.`);
}

export function checkGameOver(state) {
  if (state.phase === PHASE.GAMEOVER) return;
  if (state.rep <= 0) setGameOver(state, 'fired');
  else if (state.money < DEBT_LIMIT) setGameOver(state, 'bankrupt');
}

function setGameOver(state, reason) {
  state.phase = PHASE.GAMEOVER;
  state.modal = null;
  state.gameOver = {
    reason,
    day: state.day,
    earned: state.totals.earned,
    fixed: state.totals.fixed,
  };
  log(state, reason === 'fired' ? 'Вас уволили.' : 'Компания обанкротилась.');
}
