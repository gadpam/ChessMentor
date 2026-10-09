// Статические данные игры: карта офиса, неисправности, действия, магазин.
// Всё, что касается баланса, живёт здесь.

export const TILE = 32;            // размер клетки в пикселях
export const DAY_LENGTH = 150;     // длительность рабочей смены, секунд
export const START = { money: 300, rep: 70 };
export const MAX_REP = 100;
export const DEBT_LIMIT = -300;    // долг ниже этой суммы — банкротство
export const SPAWN = { x: 9, y: 6 };

// Легенда карты:
//   #  стена                 .  пол
//   W  рабочее место (ПК)    P  принтер     S  серверная стойка
//   H  шкаф-склад (магазин запчастей и апгрейдов)
export const SOLID_CHARS = '#WPSH';
export const DEVICE_CHARS = { W: 'pc', P: 'printer', S: 'server' };

export const MAP = [
  '########################',
  '#.W.W.W.W.......#SS..SS#',
  '#...............#......#',
  '#......................#',
  '#.W.W.W.W.......#SS....#',
  '#...............#......#',
  '#...............########',
  '#......P....P...########',
  '#...............#......#',
  '#.W.W.W.W.......#......#',
  '#..................H...#',
  '#...............#......#',
  '#.W.W.W.W.......#......#',
  '########################',
];

export const DEVICE_INFO = {
  pc:      { label: 'ПК',      reward: 120, maxTime: 75 },
  printer: { label: 'Принтер', reward: 90,  maxTime: 80 },
  server:  { label: 'Сервер',  reward: 260, maxTime: 60 },
};

// Действия, которые можно выбрать при диагностике.
//   time     — сколько секунд смены уходит на попытку
//   needs    — запчасть/лицензия со склада; без неё действие недоступно
//   consumes — что расходуется при успешном применении
export const ACTIONS = {
  cable:         { label: 'Проверить кабель питания и розетку', time: 6 },
  psu:           { label: 'Заменить блок питания',              time: 10, needs: 'psu', consumes: 'psu' },
  reinstall:     { label: 'Переустановить Windows',             time: 35 },
  ram:           { label: 'Переставить оперативную память',     time: 12 },
  net_cable:     { label: 'Проверить сетевой кабель',           time: 6 },
  net_driver:    { label: 'Переустановить драйвер сетевой карты', time: 14 },
  reboot:        { label: 'Перезагрузить устройство',           time: 8 },
  reset_pass:    { label: 'Сбросить пароль в AD',               time: 5 },
  av:            { label: 'Запустить антивирус',                time: 8,  needs: 'antivirus' },
  manual:        { label: 'Вычистить вирус вручную',            time: 45 },
  paper:         { label: 'Вытащить замятую бумагу из лотка',   time: 8 },
  toner:         { label: 'Заменить картридж',                  time: 10, needs: 'toner', consumes: 'toner' },
  driver_print:  { label: 'Переустановить драйвер принтера',    time: 30 },
  svc:           { label: 'Перезапустить службу',               time: 8 },
  disk:          { label: 'Заменить жёсткий диск',              time: 20, needs: 'hdd', consumes: 'hdd' },
  server_reboot: { label: 'Перезагрузить сервер',               time: 25 },
  backup:        { label: 'Восстановить из резервной копии',    time: 40 },
};

// Неисправности. variants — возможные жалобы, fix — действия, которые реально чинят.
// minDay — с какого дня появляется, weight — относительная частота.
export const FAULTS = [
  {
    key: 'pc_power', device: 'pc', weight: 3, minDay: 1, title: 'Не включается',
    variants: [
      { fix: ['cable'], text: 'Компьютер не реагирует на кнопку питания.' },
      { fix: ['psu'],   text: 'Из системного блока пахнет гарью, запускаться не хочет.' },
    ],
    actions: ['cable', 'psu', 'reinstall', 'ram'],
  },
  {
    key: 'pc_network', device: 'pc', weight: 3, minDay: 1, title: 'Нет сети',
    variants: [
      { fix: ['net_cable'],  text: 'Значок сети перечёркнут, интернета нет.' },
      { fix: ['net_driver'], text: 'Сеть отваливается каждые пять минут.' },
    ],
    actions: ['net_cable', 'net_driver', 'reboot', 'reinstall'],
  },
  {
    key: 'pc_password', device: 'pc', weight: 2, minDay: 1, title: 'Забыл пароль',
    variants: [
      { fix: ['reset_pass'], text: 'Пишет «неверный пароль», хотя я точно его помню.' },
    ],
    actions: ['reset_pass', 'reinstall', 'ram', 'reboot'],
  },
  {
    key: 'pc_virus', device: 'pc', weight: 2, minDay: 2, title: 'Вирус',
    variants: [
      { fix: ['av', 'manual'], text: 'Всплывают окна с рекламой, всё ужасно тормозит.' },
    ],
    actions: ['av', 'manual', 'reinstall', 'reboot'],
  },
  {
    key: 'printer_paper', device: 'printer', weight: 3, minDay: 1, title: 'Замятие бумаги',
    variants: [
      { fix: ['paper'], text: 'Принтер пишет «Замятие в лотке 2».' },
    ],
    actions: ['paper', 'driver_print', 'toner', 'reboot'],
  },
  {
    key: 'printer_toner', device: 'printer', weight: 2, minDay: 1, title: 'Пустой картридж',
    variants: [
      { fix: ['toner'], text: 'Печать полностью бледная, ничего не разобрать.' },
    ],
    actions: ['toner', 'driver_print', 'paper', 'reboot'],
  },
  {
    key: 'server_down', device: 'server', weight: 2, minDay: 2, title: 'Сервер не отвечает',
    variants: [
      { fix: ['svc'],  text: 'Файловый сервер не отвечает, бухгалтерия не может открыть базу.' },
      { fix: ['disk'], text: 'Сервер сообщает о сбое диска: SMART critical.' },
    ],
    actions: ['svc', 'disk', 'server_reboot', 'backup'],
  },
];

// Склад. once — покупается один раз; иначе это расходник (хранится количеством).
export const ITEMS = {
  antivirus: { name: 'Лицензия антивируса',               price: 220, once: true },
  shoes:     { name: 'Беговые кроссовки (+30% к ходьбе)', price: 300, once: true, speed: 1.3 },
  psu:       { name: 'Блок питания',                      price: 110 },
  toner:     { name: 'Картридж для принтера',             price: 80 },
  hdd:       { name: 'Жёсткий диск для сервера',          price: 190 },
};

export const CALLERS = [
  'Ирина Петровна (бухгалтерия)',
  'Олег (отдел продаж)',
  'Марина (HR)',
  'Сергей Викторович (директор)',
  'Дима (маркетинг)',
  'Тамара (приёмная)',
  'Антон (логистика)',
  'Лена (юристы)',
];

// Ежедневные расходы: аренда и зарплаты, растут с каждым днём.
export const EXPENSES = (day) => 100 + (day - 1) * 15;
