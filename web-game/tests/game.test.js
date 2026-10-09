import { test } from 'node:test';
import assert from 'node:assert/strict';
import { MAP, SPAWN, DAY_LENGTH, ITEMS } from '../js/data.js';
import {
  createGame, parseMap, startGame, update, applyAction, buyItem, spawnTicket,
  endDay, startNextDay, serialize, movePlayer, nearestInteractable, openInteractable,
  checkGameOver, PHASE, tileAt, deviceById, maxOpenTickets,
} from '../js/game.js';

// Детерминированный генератор для тестов.
function seeded(seed = 1) {
  let s = seed;
  return () => {
    s = (s * 16807) % 2147483647;
    return (s - 1) / 2147483646;
  };
}

function playing(seed = 1) {
  const s = createGame({ rng: seeded(seed) });
  startGame(s);
  return s;
}

test('карта: все строки одной длины, есть устройства и склад', () => {
  const m = parseMap();
  assert.equal(m.width, 24);
  assert.equal(m.height, 14);
  const count = (type) => m.devices.filter((d) => d.type === type).length;
  assert.equal(count('pc'), 16);
  assert.equal(count('printer'), 2);
  assert.equal(count('server'), 6);
  assert.ok(m.shop, 'склад должен быть на карте');
});

test('карта: кривая строка выбрасывает ошибку', () => {
  assert.throws(() => parseMap(['###', '##']), /Строка карты 1/);
});

test('старт: стоящий в точке спавна игрок не в стене', () => {
  const s = createGame({ rng: seeded() });
  assert.equal(tileAt(s, SPAWN.x, SPAWN.y), '.');
  assert.equal(s.phase, PHASE.INTRO);
  assert.equal(s.modal.type, 'intro');
});

test('движение упирается в стены и не проходит сквозь устройства', () => {
  const s = playing();
  // Игрок в офисе, идём влево за пределы карты — должны остановиться у стены.
  s.player = { x: 1.5, y: 6.5 };
  movePlayer(s, -1, 0, 1);
  assert.ok(s.player.x >= 1.0, `x=${s.player.x}`);
  // Сквозь ПК (первый W в строке 1, x=2, y=1) пройти нельзя.
  s.player = { x: 2.5, y: 2.5 };
  movePlayer(s, 0, -1, 1);
  assert.ok(s.player.y >= 2.0, `y=${s.player.y}`);
});

test('взаимодействие: ближайший объект в радиусе', () => {
  const s = playing();
  const pc = deviceById(s, 1); // ПК-1 на (2,1)
  s.player = { x: pc.x + 0.5, y: pc.y + 1.5 };
  assert.equal(nearestInteractable(s)?.id, 1);
  s.player = { x: 9.5, y: 6.5 };
  assert.equal(nearestInteractable(s), null);
});

test('заявки появляются и ограничены числом открытых', () => {
  const s = playing(7);
  for (let i = 0; i < 20; i++) spawnTicket(s);
  assert.ok(s.tickets.length <= maxOpenTickets(1));
  assert.ok(s.tickets.every((t) => deviceById(s, t.deviceId).broken));
});

test('правильное действие чинит и платит, неправильное снимает репутацию', () => {
  const s = playing(3);
  spawnTicket(s);
  const t = s.tickets[0];
  const dev = deviceById(s, t.deviceId);
  const wrong = dev.broken.actions.find((a) => !dev.broken.fix.includes(a) && !['av', 'psu', 'toner', 'hdd', 'disk'].includes(a));
  const rep0 = s.rep;
  const r1 = applyAction(s, dev.id, wrong);
  assert.equal(r1.ok, false);
  assert.equal(s.rep, rep0 - 1);
  assert.ok(dev.broken, 'устройство всё ещё сломано');

  const right = dev.broken.fix[0];
  const money0 = s.money;
  // Для действий, требующих запчасти, сначала покупаем её.
  if (right === 'psu') { s.money = 1000; buyItem(s, 'psu'); }
  if (right === 'toner') { s.money = 1000; buyItem(s, 'toner'); }
  if (right === 'disk') { s.money = 1000; buyItem(s, 'hdd'); }
  if (right === 'av') { s.money = 1000; buyItem(s, 'antivirus'); }
  const before = s.money;
  const r2 = applyAction(s, dev.id, right);
  assert.equal(r2.ok, true, JSON.stringify(r2));
  assert.equal(dev.broken, null);
  assert.equal(dev.ticketId, null);
  assert.equal(s.tickets.length, 0);
  assert.ok(s.money > before);
  assert.ok(money0 >= 0);
});

test('нужная запчасть без покупки блокирует действие', () => {
  const s = playing(5);
  const dev = deviceById(s, 1);
  dev.broken = { title: 'Тест', text: '-', fix: ['psu'], actions: ['psu', 'reboot'] };
  const r = applyAction(s, 1, 'psu');
  assert.equal(r.ok, false);
  assert.equal(r.blocked, true);
  assert.ok(dev.broken, 'поломка осталась');
  assert.equal(s.rep, 70, 'блокировка не наказывает репутацией');
});

test('склад: покупка уникальных и расходников, нехватка денег', () => {
  const s = playing();
  s.money = 500;
  assert.equal(buyItem(s, 'antivirus').ok, true);
  assert.equal(buyItem(s, 'antivirus').ok, false, 'лицензия покупается один раз');
  assert.equal(buyItem(s, 'psu').ok, true);
  assert.equal(s.inventory.psu, 1);
  s.money = 0;
  assert.equal(buyItem(s, 'toner').ok, false);
});

test('конец дня: расходы, перенос заявок, переход на следующий день', () => {
  const s = playing(9);
  spawnTicket(s);
  const money0 = s.money;
  s.dayTimeLeft = 0.01;
  update(s, 0.02);
  assert.equal(s.phase, PHASE.SUMMARY);
  assert.ok(s.summary.expenses > 0);
  assert.ok(s.money < money0);
  assert.equal(s.tickets.length, 1, 'открытая заявка переносится');
  startNextDay(s);
  assert.equal(s.phase, PHASE.PLAYING);
  assert.equal(s.day, 2);
  assert.equal(s.dayTimeLeft, DAY_LENGTH);
});

test('таймер заявки: истёкшая заявка снимает репутацию, устройство остаётся сломанным', () => {
  const s = playing(11);
  spawnTicket(s);
  const t = s.tickets[0];
  const dev = deviceById(s, t.deviceId);
  const rep0 = s.rep;
  update(s, t.timeLeft + 0.1);
  assert.equal(s.tickets.some((x) => x.id === t.id), false, 'истёкшая заявка удалена');
  assert.equal(s.rep, rep0 - 6);
  assert.ok(dev.broken, 'поломка остаётся без заявки');
  assert.equal(dev.ticketId, null);
});

test('банкротство и увольнение завершают игру', () => {
  const s = playing();
  s.rep = 0;
  checkGameOver(s);
  assert.equal(s.phase, PHASE.GAMEOVER);
  assert.equal(s.gameOver.reason, 'fired');

  const b = playing();
  b.money = -400;
  checkGameOver(b);
  assert.equal(b.gameOver.reason, 'bankrupt');
});

test('сохранение: круговой проход сериализации восстанавливает заявки и поломки', () => {
  const s = playing(21);
  spawnTicket(s);
  spawnTicket(s);
  const data = JSON.parse(JSON.stringify(serialize(s)));
  const r = createGame({ rng: seeded(21), save: data });
  assert.equal(r.phase, PHASE.PLAYING);
  assert.equal(r.tickets.length, s.tickets.length);
  assert.equal(r.money, s.money);
  for (const d of s.devices) {
    assert.deepEqual(deviceById(r, d.id).broken, d.broken);
    assert.equal(deviceById(r, d.id).ticketId, d.ticketId);
  }
});

test('симуляция 150 секунд без ошибок и с осмысленным итогом', () => {
  const s = playing(42);
  let t = 0;
  while (s.phase === PHASE.PLAYING && t < 400) {
    update(s, 0.1, { dx: Math.sin(t) > 0 ? 1 : -1, dy: 0 });
    t += 0.1;
    // Имитируем игрока, который иногда чинит первую заявку.
    if (s.tickets.length && Math.floor(t) % 9 === 0) {
      const dev = deviceById(s, s.tickets[0].deviceId);
      applyAction(s, dev.id, dev.broken.fix[0]);
    }
  }
  assert.ok([PHASE.SUMMARY, PHASE.GAMEOVER].includes(s.phase), `phase=${s.phase}`);
});
