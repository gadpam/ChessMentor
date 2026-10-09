// DOM-интерфейс: HUD, список заявок, журнал и модальные окна.

import { ACTIONS, ITEMS, DEVICE_INFO, MAX_REP, DEBT_LIMIT } from './data.js';
import { PHASE, deviceById, hasStock, maxOpenTickets } from './game.js';

const $ = (id) => document.getElementById(id);

// Маленький помощник для создания DOM без innerHTML.
export function h(tag, attrs = {}, ...children) {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs || {})) {
    if (v === undefined || v === null || v === false) continue;
    if (k === 'class') el.className = v;
    else if (k.startsWith('on') && typeof v === 'function') el.addEventListener(k.slice(2), v);
    else el.setAttribute(k, v === true ? '' : String(v));
  }
  for (const c of children.flat()) {
    if (c === undefined || c === null || c === false) continue;
    el.append(c instanceof Node ? c : document.createTextNode(String(c)));
  }
  return el;
}

const fmtTime = (sec) => {
  const s = Math.max(0, Math.ceil(sec));
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
};

// ---------- HUD ----------

export function updateHUD(state) {
  $('hud-day').textContent = state.day;
  $('hud-time').textContent = fmtTime(state.dayTimeLeft);
  $('hud-money').textContent = `${state.money} ₽`;
  $('hud-money').classList.toggle('neg', state.money < 0);
  $('hud-rep').style.width = `${Math.max(0, Math.min(100, state.rep))}%`;
  $('hud-rep-val').textContent = `${state.rep}/${MAX_REP}`;
  $('ticket-count').textContent = `${state.tickets.length}/${maxOpenTickets(state.day)}`;
}

export function renderHint(state, nearest) {
  const el = $('hint');
  if (state.phase !== PHASE.PLAYING || state.modal || !nearest) {
    el.hidden = true;
    return;
  }
  el.hidden = false;
  el.textContent = nearest.kind === 'shop'
    ? 'E — открыть склад'
    : `E — осмотреть: ${nearest.label}`;
}

// ---------- заявки ----------

const cards = new Map();

export function updateTickets(state, ui, onTrack) {
  const box = $('tickets');
  const alive = new Set(state.tickets.map((t) => t.id));
  for (const [id, el] of cards) {
    if (!alive.has(id)) {
      el.remove();
      cards.delete(id);
    }
  }
  $('tickets-empty').hidden = state.tickets.length > 0;

  for (const t of state.tickets) {
    const dev = deviceById(state, t.deviceId);
    let el = cards.get(t.id);
    if (!el) {
      el = h('div', { class: 'ticket' },
        h('div', { class: 'ticket-head' },
          h('span', { class: 'caller' }, t.caller),
          h('span', { class: 'time' }),
        ),
        h('div', { class: 'ticket-title' }, `${dev.label}: ${dev.broken ? dev.broken.title : ''}`),
        h('div', { class: 'ticket-text' }, dev.broken ? dev.broken.text : ''),
        h('div', { class: 'bar' }, h('div')),
        h('button', { class: 'ghost small', onclick: () => onTrack(dev.id) }, 'Показать на карте'),
      );
      cards.set(t.id, el);
      box.appendChild(el);
    }
    const pct = Math.max(0, (t.timeLeft / t.maxTime) * 100);
    el.querySelector('.time').textContent = fmtTime(t.timeLeft);
    el.querySelector('.bar > div').style.width = `${pct}%`;
    el.querySelector('.bar > div').classList.toggle('urgent', pct < 25);
    el.classList.toggle('tracked', ui.tracked === dev.id);
  }
}

export function renderLog(state) {
  const sig = `${state.log.length}:${state.log[0] ? state.log[0].text : ''}`;
  const list = $('log');
  if (list.dataset.sig === sig) return;
  list.dataset.sig = sig;
  list.replaceChildren(...state.log.slice(0, 14).map((e) => h('li', {}, e.text)));
}

// ---------- модальные окна ----------

// Сигнатура нужна, чтобы не перерисовывать модалку каждый кадр.
export function modalSignature(state, ui) {
  const dev = state.modal && state.modal.deviceId ? deviceById(state, state.modal.deviceId) : null;
  return [
    state.phase,
    state.modal ? state.modal.type : '-',
    dev ? `${dev.id}:${dev.broken ? dev.broken.title : 'ok'}:${dev.ticketId}` : '',
    state.money,
    JSON.stringify(state.inventory),
    ui.flash,
    state.phase === PHASE.SUMMARY ? state.day : '',
  ].join('|');
}

export function renderModal(state, ui, api) {
  const overlay = $('overlay');
  const box = $('modal');
  let content = null;

  if (state.phase === PHASE.INTRO) content = buildIntro(api);
  else if (state.phase === PHASE.GAMEOVER) content = buildGameOver(state, api);
  else if (state.phase === PHASE.SUMMARY) content = buildSummary(state, ui, api);
  else if (state.modal && state.modal.type === 'device') content = buildDevice(state, ui, api);
  else if (state.modal && state.modal.type === 'shop') content = buildShopModal(state, ui, api);

  overlay.hidden = !content;
  if (content) box.replaceChildren(content);
}

function flashNode(ui) {
  if (!ui.flash) return null;
  return h('div', { class: 'flash' }, ui.flash);
}

function buildIntro(api) {
  return h('div', { class: 'card intro' },
    h('h1', {}, 'Системный администратор'),
    h('p', {}, 'Вы — единственный сисадмин небольшого офиса. Сотрудники звонят с жалобами: компьютер не включается, принтер ничего не печатает, сервер молчит.'),
    h('p', {}, 'Звонки появляются в правой панели. Подойдите к устройству, нажмите E и выберите действие. Неверный вариант тратит время и портит репутацию.'),
    h('p', {}, 'В конце смены — расходы. Деньги за ремонт можно тратить на запчасти и апгрейды на складе (H).'),
    h('p', { class: 'muted' }, 'WASD / стрелки — ходить · E / Enter — взаимодействовать · Esc — закрыть окно'),
    h('div', { class: 'actions' }, h('button', { class: 'primary', onclick: api.onStart }, 'Начать смену')),
  );
}

function buildDevice(state, ui, api) {
  const dev = deviceById(state, state.modal.deviceId);
  if (!dev) return null;
  const info = DEVICE_INFO[dev.type];
  const ticket = dev.ticketId ? state.tickets.find((t) => t.id === dev.ticketId) : null;

  const head = h('div', { class: 'modal-head' },
    h('h2', {}, dev.label),
    h('button', { class: 'ghost small', onclick: api.onClose }, 'Esc ✕'),
  );

  if (!dev.broken) {
    return h('div', { class: 'card' },
      head,
      h('p', {}, 'Устройство работает нормально. Признаков неисправности нет.'),
      h('p', { class: 'muted' }, `Тип: ${info.label}`),
      h('div', { class: 'actions' }, h('button', { onclick: api.onClose }, 'Закрыть')),
    );
  }

  const actions = dev.broken.actions.map((id) => {
    const act = ACTIONS[id];
    const missing = act.needs && !hasStock(state, act.needs);
    return h('button', {
      class: 'action' + (missing ? ' missing' : ''),
      disabled: missing,
      onclick: () => api.onAction(dev.id, id),
    },
      h('span', { class: 'action-label' }, act.label),
      h('span', { class: 'action-meta' },
        `${act.time} с`,
        missing ? ` · нужно: ${ITEMS[act.needs].name}` : ''),
    );
  });

  return h('div', { class: 'card wide' },
    head,
    h('div', { class: 'diag-title' }, dev.broken.title),
    ticket ? h('p', { class: 'caller-line' }, `От: ${ticket.caller}`) : h('p', { class: 'muted' }, 'Заявки нет — устройство сломалось тихо. Ремонт оплачивается вполовину.'),
    h('p', { class: 'complaint' }, dev.broken.text),
    h('div', { class: 'diag-title small' }, 'Что сделать?'),
    h('div', { class: 'actions-list' }, actions),
    flashNode(ui),
  );
}

function buildShop(state, api) {
  const rows = Object.entries(ITEMS).map(([id, item]) => {
    const owned = item.once && state.inventory[id];
    const stock = item.once ? null : state.inventory[id];
    const cannot = owned || state.money < item.price;
    return h('div', { class: 'shop-row' },
      h('div', { class: 'shop-info' },
        h('div', { class: 'shop-name' }, item.name),
        h('div', { class: 'muted small' },
          owned ? 'Куплено навсегда' : (stock !== null ? `На складе: ${stock}` : 'Расходник')),
      ),
      h('button', {
        class: owned ? 'ghost' : '',
        disabled: cannot,
        onclick: () => api.onBuy(id),
      }, owned ? '✓' : `${item.price} ₽`),
    );
  });
  return h('div', { class: 'shop' }, h('div', { class: 'shop-title' }, 'Склад'), ...rows);
}

function buildShopModal(state, ui, api) {
  return h('div', { class: 'card wide' },
    h('div', { class: 'modal-head' },
      h('h2', {}, 'Склад (H)'),
      h('span', { class: 'money' }, `Баланс: ${state.money} ₽`),
    ),
    buildShop(state, api),
    flashNode(ui),
    h('div', { class: 'actions' }, h('button', { onclick: api.onClose }, 'Закрыть')),
  );
}

function buildSummary(state, ui, api) {
  const s = state.summary;
  return h('div', { class: 'card wide' },
    h('h2', {}, `Итоги дня ${s.day}`),
    h('div', { class: 'stats' },
      h('div', {}, 'Устранено проблем'), h('b', {}, s.fixed),
      h('div', {}, 'Просроченных заявок'), h('b', {}, s.expired),
      h('div', {}, 'Незакрытых заявок'), h('b', {}, s.unresolved),
      h('div', {}, 'Заработано'), h('b', {}, `+${s.earned} ₽`),
      h('div', {}, 'Расходы (аренда, зарплаты)'), h('b', {}, `−${s.expenses} ₽`),
      h('div', {}, 'Баланс'), h('b', { class: s.money < 0 ? 'neg' : '' }, `${s.money} ₽`),
      h('div', {}, 'Репутация'), h('b', {}, `${s.rep}/${MAX_REP}`),
    ),
    h('p', { class: 'muted' }, s.unresolved ? 'Незакрытые заявки перенесутся на завтра, каждая −2 репутации.' : 'Все заявки закрыты. Отлично!'),
    buildShop(state, api),
    flashNode(ui),
    h('div', { class: 'actions' },
      h('button', { class: 'primary', onclick: api.onNextDay }, `Начать день ${s.day + 1}`)),
  );
}

function buildGameOver(state, api) {
  const g = state.gameOver;
  const reason = g.reason === 'fired'
    ? 'Репутация компании упала до нуля — вас уволили.'
    : `Долги превысили ${-DEBT_LIMIT} ₽ — компания обанкротилась.`;
  return h('div', { class: 'card intro' },
    h('h1', {}, 'Игра окончена'),
    h('p', {}, reason),
    h('div', { class: 'stats' },
      h('div', {}, 'Дней отработано'), h('b', {}, g.day),
      h('div', {}, 'Устранено проблем'), h('b', {}, g.fixed),
      h('div', {}, 'Заработано всего'), h('b', {}, `${g.earned} ₽`),
    ),
    h('div', { class: 'actions' }, h('button', { class: 'primary', onclick: api.onRestart }, 'Начать заново')),
  );
}

