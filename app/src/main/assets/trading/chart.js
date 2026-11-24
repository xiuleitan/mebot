'use strict';

const library = window.klinecharts;
const chartElement = document.getElementById('chart');
const crosshairPrice = document.getElementById('crosshair-price');
const crosshairDelta = document.getElementById('crosshair-delta');
const crosshairValue = document.getElementById('crosshair-value');
const chart = library.init(chartElement, { locale: 'zh-CN', styles: 'dark' });
const store = chart.getChartStore();
const candlePane = 'candle_pane';
const timeAxisPane = 'x_axis_pane';
const fillsGroup = 'trading-fills';
const linesGroup = 'trading-lines';
const stopLossId = 'position-stop-losses';
const draftId = 'trading-draft';
const horizontalLine = 'horizontalStraightLine';
const lineHitRadius = 9;
const handleHitRadius = 13;
const utcDay = 86_400_000;
const weekMillis = 7 * utcDay;
const weekendBounds = new Map();
const periodMillis = { minute: 60_000, hour: 3_600_000, day: utcDay };
const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)');
const priceAxis = chart.getYAxes({ paneId: candlePane })[0];
let candles = [];
let symbol = null;
let period = null;
let windowRevision = 0;
let subscription = null;
let fills = [];
let lines = [];
let stopLosses = [];
let tool = null;
let pendingHistory = null;
let nextRequest = 1;
let priceDrag = null;
let selectedLineId = null;
let lineDrag = null;
let pendingLineMove = null;
let background = '#161F2C';
let up = '#53CFA7';
let down = '#F17C91';
let fontScale = 1;
let weekendCalendar = createWeekendCalendar();
let weekendFrame = 0;
let priceRangeMotionUntil = 0;
let priceRangeFrame = 0;
let lastPriceRange = null;
let lastPriceRangeTime = 0;
let customCrosshair = false;
let referencePrice = null;
let saveBarSpaceEnabled = false;
let barSpaceSaveTimer = 0;
let crosshairFrame = 0;
let lastCandleDetails = null;

function fontSize(size) {
  return Math.round(size * fontScale);
}

function send(message) {
  window.TradingHost?.event(JSON.stringify({ ...message, revision: windowRevision }));
}

function saveBarSpace() {
  if (!barSpaceSaveTimer) return;
  clearTimeout(barSpaceSaveTimer);
  barSpaceSaveTimer = 0;
  send({ type: 'barSpace', value: chart.getBarSpace().bar });
}

library.registerOverlay({
  name: 'mebotFill',
  totalStep: 2,
  needDefaultPointFigure: false,
  createPointFigures: ({ coordinates, overlay, bounding }) => {
    const marks = coordinates.map(({ x, y }, index) => ({ x, y, side: overlay.extendData[index] }))
      .filter(({ x, y }) => x >= 0 && x <= bounding.width && y >= 0 && y <= bounding.height)
      .sort((a, b) => a.y - b.y);
    const labelSize = fontSize(8.5);
    const labelGap = labelSize + 4;
    const dashWidth = 10;
    const dashBorderColor = '#FFD54A';
    const labelWidth = labelSize + 4;
    const splitSides = marks.some((mark, index) => {
      const previous = marks[index - 1];
      return previous && mark.side !== previous.side && mark.y - previous.y < labelGap;
    });
    const lastPriceY = { BUY: -Infinity, SELL: -Infinity };
    const lastLetterY = { BUY: -Infinity, SELL: -Infinity };
    const figures = [];
    for (const { x, y, side } of marks) {
      if (lastPriceY[side] === y) continue;
      lastPriceY[side] = y;

      const buy = side === 'BUY';
      const sideOffset = splitSides ? (buy ? -1 : 1) * (dashWidth + labelWidth + 2) / 2 : 0;
      const left = x + sideOffset - dashWidth / 2;
      const right = left + dashWidth;
      const showLetter = y - lastLetterY[side] >= labelGap;
      const tapLeft = left - (showLetter ? labelWidth : 2);
      const dash = { coordinates: [{ x: left, y }, { x: right, y }] };
      figures.push({
        key: side, type: 'rect',
        attrs: { x: tapLeft, y: y - 7, width: right - tapLeft + 2, height: 14 },
        styles: { style: 'fill', color: 'transparent' }
      }, {
        key: side, type: 'line', attrs: dash,
        styles: { color: dashBorderColor, size: 3, style: 'solid', lineCap: 'round' }
      }, {
        key: side, type: 'line', attrs: dash, ignoreEvent: true,
        styles: { color: '#000000', size: 1, style: 'solid', lineCap: 'round' }
      });
      if (showLetter) {
        lastLetterY[side] = y;
        figures.push({
          key: side, type: 'text',
          attrs: { x: left - 2, y, text: buy ? 'B' : 'S', align: 'right', baseline: 'middle' },
          styles: {
            color: buy ? up : down, size: labelSize, weight: 600,
            backgroundColor: buy ? '#254C41' : '#572F3D',
            style: 'stroke_fill', borderColor: '#FFFFFF', borderSize: 1, borderStyle: 'solid',
            paddingLeft: 2, paddingRight: 2, paddingTop: 1, paddingBottom: 1, borderRadius: 2
          }
        });
      }
    }
    return figures;
  }
});

library.registerFigure({
  name: 'mebotLineHit',
  draw: () => {},
  checkEventOn: ({ x, y }, { start, end }) => {
    const dx = end.x - start.x;
    const dy = end.y - start.y;
    const lengthSquared = dx * dx + dy * dy;
    const along = lengthSquared > 0 ? ((x - start.x) * dx + (y - start.y) * dy) / lengthSquared : 0;
    const t = Math.max(0, Math.min(1, along));
    return Math.hypot(x - start.x - t * dx, y - start.y - t * dy) <= lineHitRadius;
  }
});

library.registerOverlay({
  name: 'mebotSavedLine',
  totalStep: 2,
  createPointFigures: ({ coordinates, bounding }) => {
    const [first, second] = coordinates;
    if (!first) return [];
    const start = second ? first : { x: 0, y: first.y };
    const end = second ?? { x: bounding.width, y: first.y };
    return [
      { type: 'line', attrs: { coordinates: [start, end] }, ignoreEvent: true },
      { type: 'mebotLineHit', attrs: { start, end }, ignoreEvent: tool !== null }
    ];
  }
});

library.registerOverlay({
  name: 'mebotStopLosses',
  totalStep: 2,
  needDefaultPointFigure: false,
  createPointFigures: ({ coordinates, overlay, bounding, yAxis }) => {
    const levels = coordinates.map(({ y }, index) => ({ y, ...overlay.extendData[index] }))
      .filter(({ y }) => Number.isFinite(y)).sort((a, b) => a.y - b.y);
    const size = fontSize(8.5);
    const gap = size + 6;
    const top = gap / 2 + 2;
    const bottom = bounding.height - top;
    const labelX = bounding.width - (yAxis?.getBounding().width ?? 0) - 4;
    const format = chart.getChartStore().getThousandsSeparator();
    const figures = [];
    let lastLabelY = -Infinity;
    for (const [index, { y, price, side }] of levels.entries()) {
      if (y >= 0 && y <= bounding.height) {
        figures.push({
          type: 'line', ignoreEvent: true,
          attrs: { coordinates: [{ x: 0, y }, { x: bounding.width, y }] },
          styles: { color: down, size: 1, style: 'dashed', dashedValue: [5, 4] }
        });
      }
      const labelY = Math.min(bottom - (levels.length - index - 1) * gap, Math.max(top, y, lastLabelY + gap));
      lastLabelY = labelY;
      const prefix = levels.length === 1 ? '止损' : side === 'LONG' ? '多仓止损' : '空仓止损';
      const arrow = y < 0 ? ' ↑' : y > bounding.height ? ' ↓' : '';
      figures.push({
        type: 'text', ignoreEvent: true,
        attrs: { x: labelX, y: labelY, text: prefix + ' ' + format.format(price) + arrow, align: 'right', baseline: 'middle' },
        styles: {
          color: down, size, weight: 600, backgroundColor: '#432631',
          style: 'stroke_fill', borderColor: down, borderSize: 1, borderStyle: 'solid',
          paddingLeft: 4, paddingRight: 4, paddingTop: 2, paddingBottom: 2, borderRadius: 3
        }
      });
    }
    return figures;
  }
});

chart.setDataLoader({
  getBars: ({ type, timestamp, callback }) => {
    if (type === 'init') {
      callback(candles, { forward: candles.length > 0, backward: false });
      return;
    }
    if (type === 'forward' && timestamp !== null && period !== null) {
      const id = nextRequest++;
      pendingHistory = { id, callback };
      send({ type: 'older', id, before: timestamp, interval: period.id });
      return;
    }
    callback([], false);
  },
  subscribeBar: ({ callback }) => { subscription = callback; },
  unsubscribeBar: () => { subscription = null; }
});
chart.createIndicator({ id: 'trade-averages', name: 'MA', paneId: candlePane, calcParams: [7, 25, 99] });
const volumePane = 'trade-volume-pane';
chart.createIndicator({ id: 'trade-volume', name: 'VOL', paneId: volumePane, calcParams: [] });
chart.setBarSpace(6);
chart.setOffsetRightDistance(36);
chart.overrideYAxis({ paneId: candlePane, id: priceAxis.id, inside: true, createRange: easePriceRange });
for (const axis of chart.getYAxes({ paneId: volumePane })) {
  chart.overrideYAxis({ paneId: volumePane, id: axis.id, inside: true });
}

function resetPriceRangeMotion() {
  cancelAnimationFrame(priceRangeFrame);
  priceRangeFrame = 0;
  priceRangeMotionUntil = 0;
  lastPriceRange = null;
}

function easePriceRange({ defaultRange }) {
  const now = performance.now();
  const elapsed = Math.min(now - lastPriceRangeTime, 16);
  lastPriceRangeTime = now;
  if (lastPriceRange && now < priceRangeMotionUntil && !reduceMotion.matches) {
    const fromDelta = defaultRange.from - lastPriceRange.from;
    const toDelta = defaultRange.to - lastPriceRange.to;
    const height = Math.max(1, priceAxis.getBounding().height);
    const tolerance = Math.max(defaultRange.range, lastPriceRange.range) / (height * 2);
    if (Math.max(Math.abs(fromDelta), Math.abs(toDelta)) > tolerance) {
      // Ease the automatic price range when a high or low scrolls past the visible edge.
      const fraction = 1 - Math.exp(-elapsed / 36);
      const from = lastPriceRange.from + fromDelta * fraction;
      const to = lastPriceRange.to + toDelta * fraction;
      const range = to - from;
      lastPriceRange = {
        from, to, range,
        realFrom: from, realTo: to, realRange: range,
        displayFrom: from, displayTo: to, displayRange: range
      };
      if (!priceRangeFrame) {
        priceRangeFrame = requestAnimationFrame(() => {
          priceRangeFrame = 0;
          if (!priceAxis.getAutoCalcTickFlag()) return;
          chart.layout({ buildYAxisTick: true, update: true });
          if (store.getCrosshair().paneId) scheduleCrosshairRefresh();
        });
      }
      return lastPriceRange;
    }
  }
  lastPriceRange = defaultRange;
  return defaultRange;
}

function setPriceRange(range) {
  // KLineChart's manual range lets its main-pane drag pan vertically and horizontally.
  resetPriceRangeMotion();
  priceAxis.setRange(range);
  chart.resize();
  scheduleCrosshairRefresh();
}

function zoomedRange(origin, factor) {
  function edges(from, to) {
    const extra = (to - from) * (factor - 1) / 2;
    return [from - extra, to + extra];
  }
  const [from, to] = edges(origin.from, origin.to);
  const [realFrom, realTo] = edges(origin.realFrom, origin.realTo);
  const [displayFrom, displayTo] = edges(origin.displayFrom, origin.displayTo);
  return {
    from, to, range: to - from,
    realFrom, realTo, realRange: realTo - realFrom,
    displayFrom, displayTo, displayRange: displayTo - displayFrom
  };
}

// Inside axes share the main pane's hit area. Route vertical drags on the price labels here.
chartElement.addEventListener('touchstart', event => {
  priceDrag = null;
  if (lineDrag) {
    // The selected line owns this gesture, so the chart must not start its pan or long press.
    event.preventDefault();
    event.stopImmediatePropagation();
    return;
  }
  if (event.touches.length !== 1 || tool) return;
  const { clientX: x, clientY: y } = event.touches[0];
  const bounds = chart.getDom(candlePane, 'yAxis')?.getBoundingClientRect();
  if (!bounds || x < bounds.left || x > bounds.right || y < bounds.top || y > bounds.bottom) return;
  const origin = priceAxis.getRange();
  if (!Number.isFinite(origin.range) || origin.range <= 0) return;
  priceDrag = { x, y, height: bounds.height, origin: { ...origin }, active: false };
}, { capture: true, passive: false });

chartElement.addEventListener('touchmove', event => {
  if (!priceDrag || event.touches.length !== 1) return;
  const touch = event.touches[0];
  const dy = touch.clientY - priceDrag.y;
  if (!priceDrag.active) {
    const dx = Math.abs(touch.clientX - priceDrag.x);
    if (dx >= 6 && dx > Math.abs(dy)) {
      priceDrag = null;
      return;
    }
    if (Math.abs(dy) < 6) return;
    priceDrag.active = true;
  }
  event.preventDefault();
  event.stopImmediatePropagation();
  const factor = Math.exp(dy / priceDrag.height);
  setPriceRange(zoomedRange(priceDrag.origin, factor));
}, { capture: true, passive: false });

for (const event of ['touchend', 'touchcancel']) {
  chartElement.addEventListener(event, () => { priceDrag = null; }, true);
}

function barOpen(time) {
  if (period.type === 'week') {
    // Binance weekly candles start at midnight UTC on Monday.
    const day = Math.floor(time / utcDay);
    const sinceMonday = (new Date(time).getUTCDay() + 6) % 7;
    return (day - sinceMonday) * utcDay;
  }
  if (period.type === 'month') {
    const date = new Date(time);
    return Date.UTC(date.getUTCFullYear(), date.getUTCMonth(), 1);
  }
  const duration = periodMillis[period.type] * period.span;
  // Use a loaded candle's open time for the exchange's interval alignment.
  const anchor = chart.getDataList()[0]?.timestamp ?? 0;
  return anchor + Math.floor((time - anchor) / duration) * duration;
}

function createWeekendCalendar() {
  return new Intl.DateTimeFormat('en-GB', {
    timeZone: chart.getTimezone(), year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23'
  });
}

// Use UTC fields to do calendar arithmetic on the wall-clock time in the chart's zone.
function wallTime(instant) {
  const parts = {};
  for (const { type, value } of weekendCalendar.formatToParts(instant)) {
    if (type !== 'literal') parts[type] = Number(value);
  }
  return Date.UTC(parts.year, parts.month - 1, parts.day, parts.hour, parts.minute, parts.second);
}

function wallTimeToInstant(localTime) {
  const guess = localTime - (wallTime(localTime) - localTime);
  return localTime - (wallTime(guess) - guess);
}

function weekendRange(saturday) {
  if (!weekendBounds.has(saturday)) {
    weekendBounds.set(saturday, [
      wallTimeToInstant(saturday),
      wallTimeToInstant(saturday + 2 * utcDay)
    ]);
  }
  return weekendBounds.get(saturday);
}

function paintWeekendAxis() {
  const axis = chart.getDom(timeAxisPane, 'main');
  const width = chart.getSize(timeAxisPane, 'main')?.width;
  if (!axis || !width) return;
  if (!period || chart.getDataList().length === 0) {
    axis.style.backgroundImage = '';
    return;
  }

  const barWidth = chart.getBarSpace().bar;
  const firstIndex = store.coordinateToDataIndex(-barWidth);
  const lastIndex = store.coordinateToDataIndex((Math.ceil(width / barWidth) + 1) * barWidth);
  const from = store.dataIndexToTimestamp(firstIndex);
  const to = store.dataIndexToTimestamp(lastIndex);
  let index = firstIndex;
  let before = from;
  let after = store.dataIndexToTimestamp(index + 1);
  // Walk the bars once as weekend boundaries are visited in order.
  function pixelAt(time) {
    while (index < lastIndex - 1 && after < time) {
      index++;
      before = after;
      after = store.dataIndexToTimestamp(index + 1);
    }
    const fraction = after > before ? (time - before) / (after - before) : 0;
    return Math.max(0, Math.min(width, store.dataIndexToCoordinate(index + fraction)));
  }

  const localDate = new Date(wallTime(from));
  localDate.setUTCHours(4, 0, 0, 0);
  const daysSinceSaturday = (localDate.getUTCDay() + 1) % 7;
  const saturday = localDate.getTime() - daysSinceSaturday * utcDay;
  const stops = [];
  const tint = down + '40';
  for (let date = saturday; ; date += weekMillis) {
    const [start, end] = weekendRange(date);
    if (start >= to) break;
    if (end <= from) continue;
    const left = pixelAt(Math.max(from, start));
    const right = pixelAt(Math.min(to, end));
    if (right <= left) continue;
    const leftStop = left.toFixed(2) + 'px';
    const rightStop = right.toFixed(2) + 'px';
    stops.push('transparent ' + leftStop, tint + ' ' + leftStop + ' ' + rightStop, 'transparent ' + rightStop);
  }
  axis.style.backgroundImage = stops.length ? 'linear-gradient(to right, ' + stops.join(', ') + ')' : '';
}

function scheduleWeekendAxis() {
  if (weekendFrame) return;
  weekendFrame = requestAnimationFrame(() => {
    weekendFrame = 0;
    paintWeekendAxis();
  });
}

function paintFills() {
  chart.removeOverlay({ groupId: fillsGroup });
  if (!period) return;
  const availableBars = new Set(chart.getDataList().map(bar => bar.timestamp));
  const groups = new Map();
  for (const fill of fills) {
    const bar = barOpen(fill.time);
    if (!availableBars.has(bar)) continue;
    if (!groups.has(bar)) groups.set(bar, { levels: new Map(), tradeIds: { BUY: [], SELL: [] } });
    const group = groups.get(bar);
    group.levels.set(fill.side + ':' + fill.price, fill);
    group.tradeIds[fill.side].push(fill.id);
  }
  for (const [bar, { levels, tradeIds }] of groups) {
    const prices = Array.from(levels.values());
    chart.createOverlay({
      name: 'mebotFill', groupId: fillsGroup, id: 'fill:' + bar, lock: true,
      fixedZLevel: true, zLevel: 2,
      points: prices.map(fill => ({ timestamp: bar, value: fill.price })),
      extendData: prices.map(fill => fill.side),
      onClick: ({ figure }) => {
        const ids = tradeIds[figure?.key];
        if (ids) send({ type: 'fill', tradeIds: ids });
      }
    });
  }
}

function savedLinePoints(line) {
  if (line.kind === horizontalLine) {
    return [{ timestamp: chart.getDataList()[0].timestamp, value: line.price }];
  }
  return [
    { timestamp: barOpen(line.time), value: line.price },
    { timestamp: barOpen(line.endTime), value: line.endPrice }
  ];
}

function withLineMove(line, move) {
  if (move?.id !== line.id) return line;
  return move.endpoint === 0
    ? { ...line, time: move.time, price: move.price }
    : { ...line, endTime: move.time, endPrice: move.price };
}

function selectLine(id) {
  if (selectedLineId === id) return;
  const before = selectedLineId;
  selectedLineId = id;
  function styleLine(lineId, selected) {
    if (lineId === null) return;
    const overlay = chart.getOverlays({ id: lineId, groupId: linesGroup })[0];
    if (!overlay) return;
    const handles = selected && overlay.points.length === 2;
    chart.overrideOverlay({
      id: lineId, zLevel: selected ? 1 : 0,
      needDefaultPointFigure: handles, needDefaultXAxisFigure: handles, needDefaultYAxisFigure: selected,
      styles: { line: { size: selected ? 2 : 1 }, point: { radius: 5, borderSize: 2 } }
    });
  }
  styleLine(before, false);
  styleLine(id, true);
}

function paintSavedLines() {
  if (!period || chart.getDataList().length === 0) {
    selectLine(null);
    chart.removeOverlay({ groupId: linesGroup });
    return;
  }
  const existing = new Set(chart.getOverlays({ groupId: linesGroup }).map(overlay => overlay.id));
  const move = lineDrag?.move ?? pendingLineMove?.move;
  for (const line of lines) {
    const points = savedLinePoints(withLineMove(line, move));
    if (existing.delete(line.id)) {
      chart.overrideOverlay({ id: line.id, points });
    } else {
      chart.createOverlay({
        name: 'mebotSavedLine', id: line.id, groupId: linesGroup, paneId: candlePane,
        lock: true, fixedZLevel: true, points,
        onClick: () => selectLine(line.id),
        onDeselected: () => { if (selectedLineId === line.id) selectLine(null); },
        onRightClick: event => event.preventDefault()
      });
    }
  }
  for (const id of existing) {
    if (selectedLineId === id) selectLine(null);
    chart.removeOverlay({ id });
  }
}

function paintStopLosses() {
  const timestamp = chart.getDataList()[0]?.timestamp;
  if (timestamp === undefined || stopLosses.length === 0) {
    chart.removeOverlay({ id: stopLossId });
    return;
  }
  const points = stopLosses.map(({ price }) => ({ timestamp, value: Number(price) }));
  if (chart.getOverlays({ id: stopLossId }).length > 0) {
    chart.overrideOverlay({ id: stopLossId, points, extendData: stopLosses });
  } else {
    chart.createOverlay({
      name: 'mebotStopLosses', id: stopLossId, paneId: candlePane, lock: true, fixedZLevel: true, zLevel: 1,
      points, extendData: stopLosses
    });
  }
}

function cancelLineDrag() {
  if (!lineDrag) return;
  lineDrag.cancelled = true;
  if (lineDrag.move) {
    lineDrag.move = null;
    paintSavedLines();
  }
}

chartElement.addEventListener('pointerdown', event => {
  if (lineDrag && event.pointerId !== lineDrag.pointerId) cancelLineDrag();
  if (lineDrag || tool || pendingLineMove || !event.isPrimary || event.button !== 0) return;
  const line = lines.find(item => item.id === selectedLineId);
  if (!line) return;
  const plot = chart.getDom(candlePane, 'main')?.getBoundingClientRect();
  if (!plot || !chart.getDataList().length) return;
  const x = event.clientX - plot.left;
  const y = event.clientY - plot.top;
  if (x < 0 || x >= plot.width || y < 0 || y >= plot.height) return;
  const points = savedLinePoints(line);
  const coordinates = chart.convertToPixel(points, { paneId: candlePane });
  const horizontal = line.kind === horizontalLine;
  const distances = coordinates.map(point => horizontal ? Math.abs(point.y - y) : Math.hypot(point.x - x, point.y - y));
  const endpoint = distances[1] < distances[0] ? 1 : 0;
  if (!(distances[endpoint] <= (horizontal ? lineHitRadius : handleHitRadius))) return;
  lineDrag = {
    line, endpoint, pointerId: event.pointerId, x: event.clientX, y: event.clientY,
    position: coordinates[endpoint], timestamp: points[endpoint].timestamp, move: null, cancelled: false
  };
  chartElement.setPointerCapture(event.pointerId);
  chart.executeAction('onCrosshairChange', null);
  scheduleCrosshairRefresh();
  event.preventDefault();
}, { capture: true, passive: false });

function previewLineDrag(event) {
  const drag = lineDrag;
  if (!drag || drag.cancelled || drag.pointerId !== event.pointerId) return;
  const horizontal = drag.line.kind === horizontalLine;
  const dx = horizontal ? 0 : event.clientX - drag.x;
  const dy = event.clientY - drag.y;
  if (!drag.move && Math.hypot(dx, dy) < 3) return;
  const plot = chart.getDom(candlePane, 'main')?.getBoundingClientRect();
  if (!plot || !chart.getDataList().length) return;
  const { timestamp, value } = chart.convertFromPixel({
    x: Math.max(0, Math.min(plot.width - 1, drag.position.x + dx)),
    y: Math.max(0, Math.min(plot.height - 1, drag.position.y + dy))
  }, { paneId: candlePane });
  const startingPrice = drag.endpoint === 0 ? drag.line.price : drag.line.endPrice;
  const startingTime = horizontal ? null : (drag.endpoint === 0 ? drag.line.time : drag.line.endTime);
  const price = Math.abs(dy) < 0.5 ? startingPrice : Number(value?.toFixed(chart.getSymbol().pricePrecision));
  if (!Number.isFinite(price) || price <= 0 || (!horizontal && !Number.isSafeInteger(timestamp))) return;
  const time = horizontal ? null : (Math.abs(dx) < 0.5 || timestamp === drag.timestamp ? startingTime : barOpen(timestamp));
  if (time !== null && (!Number.isSafeInteger(time) || time <= 0)) return;
  const move = time === startingTime && price === startingPrice ? null : { id: drag.line.id, endpoint: drag.endpoint, time, price };
  if (move?.price === drag.move?.price && move?.time === drag.move?.time) return;
  drag.move = move;
  chart.overrideOverlay({ id: drag.line.id, points: savedLinePoints(withLineMove(drag.line, move)) });
}

chartElement.addEventListener('pointermove', previewLineDrag);
chartElement.addEventListener('pointerup', event => {
  if (lineDrag?.pointerId !== event.pointerId) return;
  previewLineDrag(event);
  const move = lineDrag.move;
  lineDrag = null;
  if (move) {
    const request = nextRequest++;
    pendingLineMove = { request, move };
    send({ type: 'lineMove', request, symbol, ...move });
  }
});
for (const event of ['pointercancel', 'lostpointercapture']) {
  chartElement.addEventListener(event, input => {
    if (lineDrag?.pointerId !== input.pointerId) return;
    cancelLineDrag();
    lineDrag = null;
  });
}

function chooseTool(next) {
  if (next === tool) return;
  chart.removeOverlay({ id: draftId });
  tool = next;
  if (!tool) return;
  cancelLineDrag();
  selectLine(null);
  const kind = tool;
  chart.createOverlay({
    name: kind, id: draftId, paneId: candlePane,
    onDrawEnd: ({ overlay }) => send({
      type: 'line', kind,
      points: overlay.points.map(point => ({ time: point.timestamp, price: point.value }))
    })
  });
}

function applyPalette(palette) {
  background = palette.background;
  up = palette.up;
  down = palette.down;
  fontScale = palette.fontScale;
  const ink = palette.ink;
  const grid = palette.grid;
  const accent = palette.accent;
  const smallText = fontSize(8.5);
  const tickText = { color: ink, size: smallText, marginStart: 4, marginEnd: 4 };
  const axisLine = { color: grid };
  const priceLabel = { color: background, size: smallText, paddingTop: 3, paddingBottom: 3 };
  const crosshair = {
    line: { color: ink, style: 'dashed', dashedValue: [3, 3] },
    text: { color: palette.text, backgroundColor: grid, size: smallText }
  };

  document.body.style.backgroundColor = background;
  crosshairPrice.style.setProperty('--crosshair-neutral', grid);
  crosshairPrice.style.setProperty('--crosshair-text', palette.text);
  chart.setStyles({
    grid: {
      horizontal: { color: grid, style: 'solid' },
      vertical: { color: grid, style: 'solid' }
    },
    candle: {
      bar: {
        upColor: up, upBorderColor: up, upWickColor: up,
        downColor: down, downBorderColor: down, downWickColor: down,
        noChangeColor: ink, noChangeBorderColor: ink, noChangeWickColor: ink
      },
      priceMark: {
        high: { color: ink, textSize: smallText },
        low: { color: ink, textSize: smallText },
        last: {
          upColor: up, downColor: down, noChangeColor: ink,
          line: { style: 'dashed', dashedValue: [3, 3] },
          text: priceLabel
        }
      },
      tooltip: {
        showRule: customCrosshair ? 'none' : 'follow_cross',
        showType: 'rect',
        title: { show: false },
        legend: {
          size: smallText, color: palette.text,
          marginTop: 2, marginBottom: 2, marginLeft: 3, marginRight: 3,
          template: [
            { title: '', value: '{time}' },
            { title: '开', value: '{open}' },
            { title: '高', value: '{high}' },
            { title: '低', value: '{low}' },
            { title: '收', value: '{close}' }
          ]
        },
        rect: { color: background, borderColor: grid }
      }
    },
    indicator: {
      bars: [{ upColor: up, downColor: down, noChangeColor: ink }],
      lines: [
        { color: '#EDB47B', size: 1 },
        { color: '#B8A1F0', size: 1 },
        { color: '#79AFE7', size: 1 }
      ],
      tooltip: {
        title: { show: false },
        legend: { size: smallText, color: ink, marginTop: 2, marginBottom: 2, marginLeft: 2, marginRight: 6 }
      }
    },
    xAxis: {
      size: fontSize(22) * 0.75, axisLine, tickLine: { show: false },
      tickText: { ...tickText, marginStart: 2, marginEnd: 2 }
    },
    yAxis: { axisLine: { show: false }, tickLine: { show: false }, tickText },
    separator: { color: grid, activeBackgroundColor: grid },
    crosshair: {
      horizontal: { ...crosshair, text: { ...crosshair.text, show: !customCrosshair } },
      vertical: { ...crosshair, text: { ...crosshair.text, paddingTop: 2, paddingBottom: 2 } }
    },
    overlay: { line: { color: accent, size: 1 } }
  });
  const { size, paddingTop, paddingBottom, family, weight } = chart.getStyles().candle.priceMark.last.text;
  crosshairPrice.style.height = (size + paddingTop + paddingBottom) + 'px';
  crosshairPrice.style.fontSize = size + 'px';
  crosshairPrice.style.fontFamily = family + ', sans-serif';
  crosshairPrice.style.fontWeight = weight;
  chart.setPaneOptions({ id: volumePane, height: fontSize(33), minHeight: fontSize(27), dragEnabled: false });
}

function sendCandleDetails(details) {
  const key = windowRevision + ':' + JSON.stringify(details);
  if (key === lastCandleDetails) return;
  lastCandleDetails = key;
  send({ type: 'candleDetails', details });
}

function refreshCrosshair() {
  const { paneId, x, y, kLineData: candle } = store.getCrosshair();
  if (!customCrosshair || !paneId || !candle || !Number.isFinite(y)) {
    crosshairPrice.hidden = true;
    sendCandleDetails(null);
    return;
  }

  const plot = chart.getDom(candlePane, 'main')?.getBoundingClientRect();
  sendCandleDetails({
    time: candle.timestamp, open: candle.open, high: candle.high, low: candle.low, close: candle.close,
    onLeft: !plot || x >= plot.width / 2
  });

  const bounds = chart.getDom(paneId, 'main')?.getBoundingClientRect();
  const { value } = chart.convertFromPixel({ y }, { paneId });
  if (!bounds || !Number.isFinite(value) || y < 0 || y > bounds.height) {
    crosshairPrice.hidden = true;
    return;
  }

  const isPrice = paneId === candlePane;
  const { pricePrecision, volumePrecision } = chart.getSymbol();
  const precision = isPrice ? pricePrecision : volumePrecision;
  const formatted = value.toFixed(precision);
  const text = isPrice ? formatted : store.getInnerFormatter().formatBigNumber(formatted);
  crosshairValue.textContent = store.getThousandsSeparator().format(text);
  const current = referencePrice ?? chart.getDataList().at(-1)?.close;
  const comparable = isPrice && Number.isFinite(current) && current > 0;
  const roundedCurrent = comparable ? Number(current.toFixed(pricePrecision)) : 0;
  const delta = roundedCurrent > 0 ? (Number(formatted) - roundedCurrent) / roundedCurrent * 100 : 0;
  crosshairDelta.hidden = !comparable || roundedCurrent <= 0;
  if (!crosshairDelta.hidden) crosshairDelta.textContent = (delta > 0 ? '+' : '') + delta.toFixed(2) + '%';
  crosshairPrice.style.top = (bounds.top + y) + 'px';
  crosshairPrice.style.right = (window.innerWidth - bounds.right) + 'px';
  crosshairPrice.hidden = false;
}

function scheduleCrosshairRefresh() {
  if (!customCrosshair || crosshairFrame) return;
  crosshairFrame = requestAnimationFrame(() => {
    crosshairFrame = 0;
    refreshCrosshair();
  });
}

window.tradeChart = {
  update(payload) {
    if (Object.hasOwn(payload, 'customCrosshair') && customCrosshair !== payload.customCrosshair) {
      customCrosshair = payload.customCrosshair;
      if (!customCrosshair) refreshCrosshair();
      if (!payload.palette) chart.setStyles({
        candle: { tooltip: { showRule: customCrosshair ? 'none' : 'follow_cross' } },
        crosshair: { horizontal: { text: { show: !customCrosshair } } }
      });
    }
    if (Object.hasOwn(payload, 'referencePrice')) referencePrice = payload.referencePrice;
    if (payload.palette) applyPalette(payload.palette);
    if (payload.timezone) {
      chart.setTimezone(payload.timezone);
      weekendCalendar = createWeekendCalendar();
      weekendBounds.clear();
    }
    if (payload.fills) fills = payload.fills;
    if (payload.lines) lines = payload.lines;
    if (payload.stopLosses) stopLosses = payload.stopLosses;
    if (Number.isFinite(payload.initialBarSpace)) {
      chart.setBarSpace(payload.initialBarSpace);
      saveBarSpaceEnabled = true;
    }

    if (payload.window) {
      resetPriceRangeMotion();
      cancelLineDrag();
      const window = payload.window;
      candles = window.bars;
      windowRevision = window.revision;
      const nextPeriod = window.period;
      const symbolChanged = symbol !== window.symbol;
      const samePeriod = period?.id === nextPeriod.id;
      const snapshot = chart.getDataList();
      const range = chart.getVisibleRange();
      const focus = !symbolChanged && samePeriod && range.to < snapshot.length - 2
        ? snapshot[Math.floor((range.from + range.to) / 2)]?.timestamp
        : null;
      pendingHistory = null;
      period = nextPeriod;
      if (symbolChanged || !samePeriod) {
        saveBarSpace();
        priceDrag = null;
        selectLine(null);
        if (customCrosshair) chart.executeAction('onCrosshairChange', null);
        priceAxis.setAutoCalcTickFlag(true);
      }
      if (symbolChanged) {
        pendingLineMove = null;
        lines = payload.lines ?? [];
        stopLosses = payload.stopLosses ?? [];
        symbol = window.symbol;
        chart.setSymbol({ ticker: symbol, pricePrecision: window.pricePrecision, volumePrecision: 3 });
      }
      if (!samePeriod) chart.setPeriod({ type: period.type, span: period.span });
      else if (!symbolChanged) chart.resetData();
      if (symbolChanged) chart.scrollToRealTime(0);
      if (focus != null && chart.getDataList().length > 0) chart.scrollToTimestamp(focus, 0);
      chart.removeOverlay({ id: draftId });
      tool = null;
    }
    let newBar = false;
    if (payload.tick && subscription) {
      const oldTime = chart.getDataList().at(-1)?.timestamp;
      subscription(payload.tick);
      newBar = oldTime !== payload.tick.timestamp;
    }
    if (payload.window || payload.fills || payload.palette || newBar) paintFills();
    if (payload.window || payload.lines || newBar) paintSavedLines();
    if (payload.window || payload.stopLosses || newBar) paintStopLosses();
    if (payload.window || payload.palette || payload.timezone || newBar) scheduleWeekendAxis();
    if (Object.hasOwn(payload, 'tool')) chooseTool(payload.tool);
    if (Number.isFinite(payload.focusTime) && period && chart.getDataList().length > 0) {
      const target = barOpen(payload.focusTime);
      if (chart.getDataList().some(bar => bar.timestamp === target)) {
        resetPriceRangeMotion();
        chart.scrollToTimestamp(target, 0);
        const { x } = chart.convertToPixel({ timestamp: target });
        chart.executeAction('onCrosshairChange', { x, y: 36, paneId: candlePane });
      }
    }
    scheduleCrosshairRefresh();
  },

  lineMoveSaved(request, saved) {
    if (pendingLineMove?.request !== request) return;
    if (saved) lines = lines.map(line => withLineMove(line, pendingLineMove.move));
    pendingLineMove = null;
    paintSavedLines();
  },

  history(id, bars, hasEarlier) {
    if (pendingHistory?.id !== id) return;
    const { callback } = pendingHistory;
    pendingHistory = null;
    callback(bars, { forward: hasEarlier, backward: false });
    paintFills();
    paintSavedLines();
    paintStopLosses();
    scheduleWeekendAxis();
    scheduleCrosshairRefresh();
  }
};

for (const action of ['onScroll', 'onZoom', 'onVisibleRangeChange']) {
  chart.subscribeAction(action, () => {
    if (action === 'onScroll') priceRangeMotionUntil = performance.now() + 260;
    scheduleWeekendAxis();
    scheduleCrosshairRefresh();
  });
}
chart.subscribeAction('onZoom', () => {
  resetPriceRangeMotion();
  if (!saveBarSpaceEnabled) return;
  clearTimeout(barSpaceSaveTimer);
  barSpaceSaveTimer = setTimeout(saveBarSpace, 140);
});
for (const event of ['touchend', 'touchcancel', 'mouseup']) {
  chartElement.addEventListener(event, saveBarSpace, { capture: true, passive: true });
}
document.addEventListener('visibilitychange', () => { if (document.hidden) saveBarSpace(); });
chart.subscribeAction('onCrosshairChange', scheduleCrosshairRefresh);
// Clearing a crosshair does not emit onCrosshairChange in the bundled chart version.
for (const event of ['touchstart', 'touchmove', 'touchend', 'touchcancel', 'mousedown', 'mousemove', 'mouseup', 'mouseleave']) {
  chartElement.addEventListener(event, scheduleCrosshairRefresh, { capture: true, passive: true });
}
new ResizeObserver(() => {
  chart.resize();
  scheduleWeekendAxis();
  scheduleCrosshairRefresh();
}).observe(chartElement);
send({ type: 'ready' });
