/* Today: the date and the checklist lead. Reminders sit on a time ruler. Review requests and Active Topics follow. */
(function () {
  'use strict';
  const T = window.Thread;
  const S = T.sel;
  const TM = T.time;
  const ui = T.ui;
  const esc = T.esc;
  const icon = T.icon;
  const store = T.store;

  const HOUR = 54;
  const CLOCKS = [['08:30', '08:30, start of day'], ['11:20', '11:20, late morning'], ['14:05', '14:05, after lunch'], ['17:30', '17:30, end of day'], ['19:30', '19:30, evening']];
  const GROUP_NOTE = {
    overdue: 'Deadline passed',
    today: '',
    due: 'Not planned today, deadline is today',
    earlier: 'Planned on an earlier day, still open'
  };

  function head(groups) {
    const all = ['overdue', 'today', 'due', 'earlier'].reduce((a, g) => a.concat(groups[g]), []);
    const open = all.filter((k) => !k.done).length;
    const done = all.filter((k) => k.done).length;
    const late = groups.overdue.filter((k) => !k.done).length;
    return '<div class="today-head">' +
      '<h1 class="datehead" aria-label="Today, Friday 13 November 2026"><span class="date-num num" aria-hidden="true">13</span><span class="date-words" aria-hidden="true"><span class="date-day">Friday</span><span class="date-month">November 2026</span></span></h1>' +
      '<div class="today-sum">' +
        '<div class="stat' + (late ? ' late' : '') + '"><span class="num">' + late + '</span><span>Overdue</span></div>' +
        '<div class="stat"><span class="num">' + open + '</span><span>Open today</span></div>' +
        '<div class="stat"><span class="num">' + done + '</span><span>Done today</span></div>' +
      '</div>' +
      ui.keycap('Capture', 'N', 'data-act="capture"') +
    '</div>';
  }

  function checklist(groups) {
    const order = ['overdue', 'today', 'due', 'earlier'];
    const any = order.some((g) => groups[g].length);
    if (!any) {
      return '<section class="checklist" aria-label="Checklist">' + ui.empty('Nothing planned for today',
        'Plan a task for today, give one a deadline, or capture something new. Tasks without a day stay in their topic.',
        ui.keycap('Capture', 'N', 'data-act="capture"'), 'h2') + '</section>';
    }
    return '<section class="checklist" aria-label="Checklist">' + order.filter((g) => groups[g].length).map((g) => {
      const list = groups[g];
      return '<div class="group" data-group="' + g + '"><h2 class="group-head">' + esc(S.GROUP_LABEL[g]) + '<span class="count-num">' + list.filter((k) => !k.done).length + '</span>' +
        (GROUP_NOTE[g] ? '<span class="group-note">' + esc(GROUP_NOTE[g]) + '</span>' : '') + '</h2>' +
        list.map((k) => ui.taskRow(k, { planned: g === 'earlier', standalone: true })).join('') + '</div>';
    }).join('') + '</section>';
  }

  function ruler() {
    const list = S.todayReminders();
    const now = TM.now();
    const nowMin = now.getHours() * 60 + now.getMinutes();
    const mins = list.map((x) => { const d = TM.parse(x.r.at); return d.getHours() * 60 + d.getMinutes(); });
    const startH = Math.min(8, Math.floor(Math.min.apply(null, mins.concat([nowMin])) / 60));
    const endH = Math.max(18, Math.ceil((Math.max.apply(null, mins.concat([nowMin])) + 30) / 60));
    let hours = '';
    for (let h = startH; h <= endH; h++) hours += '<div class="hour" style="top:' + ((h - startH) * HOUR) + 'px"><span>' + TM.pad(h) + '</span></div>';
    const nowTop = ((nowMin - startH * 60) / 60) * HOUR;
    const rems = list.map((x, i) => {
      const top = ((mins[i] - startH * 60) / 60) * HOUR - 14;
      const due = x.state === 'due';
      return '<div class="rem ' + x.state + '" style="top:' + top + 'px" data-top="' + top + '">' +
        '<span class="rem-time num">' + esc(TM.fmtTime(x.r.at)) + '</span>' +
        '<span class="rem-text">' + esc(x.r.text) + (due ? ' <span class="rem-state">Due now</span>' : '') + (x.state === 'dismissed' ? '<span class="vh"> (dismissed)</span>' : '') + '</span>' +
        ui.reminderTarget(x.r) +
        (due ? '<div class="rem-actions"><button type="button" class="btn btn-sm" data-act="dismissReminder" data-id="' + x.r.id + '">Dismiss</button><button type="button" class="btn btn-sm btn-ghost" data-act="snoozeReminder" data-id="' + x.r.id + '">Snooze 1 h</button></div>' : '') +
      '</div>';
    }).join('');
    const height = (endH - startH) * HOUR + 10;
    return '<aside class="ruler-card" id="reminders" aria-labelledby="remH2">' +
      '<div class="ruler-head"><h2 class="sec-title" id="remH2">Reminders<span class="count">' + list.filter((x) => x.state !== 'dismissed').length + '</span></h2>' +
        '<button type="button" class="btn btn-sm" data-act="remind">' + icon('plus') + 'Add</button></div>' +
      ui.sim('Demo clock', 'demoClock', CLOCKS, T.state().sim.clock, 'aria-label="Demo clock, simulation"') +
      (list.length ? '<div class="ruler" style="height:' + height + 'px">' + hours +
        '<div class="nowline" style="top:' + nowTop + 'px" aria-hidden="true"><span>' + esc(T.state().sim.clock) + '</span></div>' + rems + '</div>' :
        '<p class="tray-empty">No reminders today. Add one to a task, note or topic.</p>') +
      '<p class="ruler-foot">Reminders nudge at a time. Planned day and deadline decide the checklist.</p>' +
    '</aside>';
  }

  function reviews() {
    const s = T.state();
    let body;
    if (!s.sim.githubConnected) {
      body = '<p class="tray-empty">GitHub is not connected in this demo. <a href="settings.html#connections">Connect it in Settings</a> to see review requests.</p>';
    } else {
      const list = s.prs.filter((p) => p.requested);
      body = list.length ? list.map((p) => '<a class="rowlink" href="prs.html?id=' + p.id + '&amp;tab=review">' + ui.prRow(p) + '</a>').join('') : '<p class="tray-empty">No reviews requested from you.</p>';
    }
    const n = s.sim.githubConnected ? s.prs.filter((p) => p.requested).length : 0;
    return '<section class="tray-sec" aria-labelledby="revH"><h2 class="sec-title" id="revH">' + icon('pr') + 'Review requested<span class="count">' + n + '</span></h2>' +
      '<div class="tray">' + body + '</div></section>';
  }

  function activeTopics() {
    const list = S.activeTopics();
    let body;
    if (!list.length) {
      body = ui.empty('No active topics', 'Topics in an Active status show here with their next step. Change a status in Topics.', '<a class="btn" href="topics.html">' + icon('folder') + 'Open Topics</a>', 'h3');
    } else {
      body = '<div class="atgrid">' + list.map((t) => {
        const c = S.topicColor(t);
        return '<a class="atcard" href="' + ui.topicHref(t.id) + '" style="--c:' + c + '">' + ui.ttab(t, { link: false }) +
          '<span class="atcard-body">' + ui.status(S.statusById(t.statusId)) +
            '<span class="next"><span class="label">Next step</span><span class="val">' + (t.next ? esc(t.next) : '<span class="faint">No next step yet</span>') + '</span></span>' +
            ui.pips(S.topicProgress(t.id), c) + '</span></a>';
      }).join('') + '</div>';
    }
    return '<section class="active-sec" aria-labelledby="actH"><h2 class="sec-title" id="actH">' + '<span class="status" data-cat="active"><span class="tile">' + T.glyph('active') + '</span></span>Active topics<span class="count">' + list.length + '</span></h2>' + body + '</section>';
  }

  function render() {
    const groups = S.todayGroups();
    return head(groups) +
      '<div class="today-grid"><div>' + checklist(groups) + reviews() + '</div>' + ruler() + '</div>' +
      activeTopics();
  }

  /* Push overlapping reminder cards down so each keeps its time order and stays readable. */
  function mount(root) {
    const ruler = root.querySelector('.ruler');
    if (!ruler || T.narrow()) return;
    let bottom = -Infinity;
    root.querySelectorAll('.rem').forEach((el) => {
      const want = parseFloat(el.dataset.top);
      const top = Math.max(want, bottom + 8);
      el.style.top = top + 'px';
      bottom = top + el.offsetHeight;
    });
    if (bottom + 12 > ruler.offsetHeight) ruler.style.height = (bottom + 12) + 'px';
  }

  T.act.demoClock = (el) => {
    store.update((s) => { s.sim.clock = el.value; });
    T.toast('Demo clock set to ' + el.value);
  };

  T.views.today = { title: 'Today', render: render, mount: mount };
})();
