/* Inbox: a triage desk. Pick captures on the left, file them into a Topic tab on the right, or keep them standalone. */
(function () {
  'use strict';
  const T = window.Thread;
  const S = T.sel;
  const TM = T.time;
  const ui = T.ui;
  const esc = T.esc;
  const icon = T.icon;
  const store = T.store;

  const picked = new Set();
  let cur = null;
  let target = null;

  function chosen(items) {
    const list = items.filter((i) => picked.has(i.id));
    return list.length ? list : items.filter((i) => i.id === cur);
  }

  function render() {
    const items = S.inboxItems();
    Array.from(picked).forEach((id) => { if (!items.some((i) => i.id === id)) picked.delete(id); });
    if (!items.some((i) => i.id === cur)) cur = items.length ? items[0].id : null;
    if (target && !S.topicById(target)) target = null;

    const head = T.pageHead('Inbox', items.length ? items.length + ' capture' + (items.length === 1 ? '' : 's') + ' without a topic. File each one, or keep it standalone.' : 'Captures without a topic wait here.',
      '<button type="button" class="btn" data-act="capture" data-topic="">' + icon('plus') + 'Capture</button>');
    if (!items.length) {
      return head + ui.empty('Inbox is clear', 'Anything you capture without a topic lands here, so you can decide later where it belongs.', ui.keycap('Capture', 'N', 'data-act="capture" data-topic=""'));
    }

    const sel = chosen(items);
    const allPicked = picked.size === items.length;
    const rows = items.map((i) => {
      const isCur = i.id === cur;
      const kindWord = i.kind === 'task' ? 'Task' : 'Note';
      return '<div class="crow' + (isCur || picked.has(i.id) ? ' sel' : '') + '">' +
        '<label class="checkhit"><input type="checkbox" class="check" data-change="inboxPick" data-id="' + i.id + '"' + (picked.has(i.id) ? ' checked' : '') + ' aria-label="Select ' + esc(i.title) + '"></label>' +
        '<span class="kind" aria-hidden="true">' + icon(i.kind === 'task' ? 'check' : 'note') + '</span>' +
        '<button type="button" class="linkbtn crow-main" data-act="inboxFocus" data-id="' + i.id + '"' + (isCur ? ' aria-current="true"' : '') + '><span class="ctitle">' + esc(i.title) + '</span><span class="cmeta">' + kindWord + ', captured ' + esc(TM.fmtStamp(i.captured)) + '</span></button>' +
        '<span class="cside"><button type="button" class="btn btn-sm btn-ghost" data-act="inboxOpen" data-kind="' + i.kind + '" data-id="' + i.id + '">Open</button></span>' +
      '</div>';
    }).join('');

    const topics = T.state().topics.filter((t) => S.topicCat(t) !== 'done');
    const tTarget = S.topicById(target);
    const what = sel.length === 1 ? '“' + sel[0].title + '”' : sel.length + ' captures';
    const next = items.find((i) => sel.every((x) => x.id !== i.id));

    const desk = '<div class="desk">' +
      '<section class="capture-list" aria-label="Captures">' +
        '<div class="capture-list-head"><label class="checkhit" style="width:auto;gap:10px;display:inline-flex"><input type="checkbox" class="check" data-change="inboxPickAll"' + (allPicked ? ' checked' : '') + ' aria-label="Select all captures"><span>' + (picked.size ? picked.size + ' selected' : 'Select to file several at once') + '</span></label></div>' +
        rows + '</section>' +
      '<section class="filing" aria-labelledby="fileH">' +
        '<h2 id="fileH">File ' + esc(what) + '</h2>' +
        '<div><p class="label" id="fileTo" style="margin-bottom:8px">Into topic</p><div class="ttab-row" role="group" aria-labelledby="fileTo">' +
          topics.map((t) => '<button type="button" class="ttab md target" style="--c:' + S.topicColor(t) + '" aria-pressed="' + (t.id === target) + '" data-act="inboxTarget" data-id="' + t.id + '">' + esc(t.name) + '</button>').join('') +
        '</div></div>' +
        '<div class="filing-actions">' +
          '<button type="button" class="btn-key" data-act="inboxFile"' + (tTarget ? '' : ' disabled') + '>' + icon('folder') + '<span>' + (tTarget ? 'File to ' + esc(tTarget.name) : 'Choose a topic to file') + '</span></button>' +
          '<button type="button" class="btn" data-act="inboxStandalone">' + icon('check') + 'Keep standalone</button>' +
        '</div>' +
        '<p class="rule">Both clear the Inbox entry. Nothing is deleted, and tasks keep their dates.</p>' +
        (next ? '<p class="rule">Next up: <strong style="color:var(--text-2)">' + esc(next.title) + '</strong></p>' : '') +
      '</section></div>';
    return head + desk;
  }

  function file(topicId) {
    const items = S.inboxItems();
    const sel = chosen(items);
    if (!sel.length) return;
    const before = sel.map((i) => ({ kind: i.kind, id: i.id, topicId: i.obj.topicId, topicIds: i.obj.topicIds ? i.obj.topicIds.slice() : null }));
    store.update((s) => {
      sel.forEach((i) => {
        if (i.kind === 'task') { const k = s.tasks.find((x) => x.id === i.id); k.inbox = false; k.topicId = topicId || null; }
        else { const n = s.notes.find((x) => x.id === i.id); n.inbox = false; if (topicId && n.topicIds.indexOf(topicId) === -1) n.topicIds.push(topicId); }
      });
    });
    picked.clear();
    target = null;
    const t = S.topicById(topicId);
    const n = sel.length === 1 ? '1 capture' : sel.length + ' captures';
    T.toast(t ? 'Filed ' + n + ' to ' + t.name : 'Kept ' + n + ' standalone', {
      undo: () => store.update((s) => {
        before.forEach((b) => {
          if (b.kind === 'task') { const k = s.tasks.find((x) => x.id === b.id); if (k) { k.inbox = true; k.topicId = b.topicId; } }
          else { const nn = s.notes.find((x) => x.id === b.id); if (nn) { nn.inbox = true; nn.topicIds = b.topicIds; } }
        });
      })
    });
  }

  T.act.inboxPick = (el) => { if (el.checked) picked.add(el.dataset.id); else picked.delete(el.dataset.id); T.render(); };
  T.act.inboxPickAll = (el) => { S.inboxItems().forEach((i) => { if (el.checked) picked.add(i.id); else picked.delete(i.id); }); T.render(); };
  T.act.inboxFocus = (el) => { cur = el.dataset.id; T.render(); };
  T.act.inboxTarget = (el) => { target = target === el.dataset.id ? null : el.dataset.id; T.render(); };
  T.act.inboxFile = () => { if (target) file(target); };
  T.act.inboxStandalone = () => file(null);
  T.act.inboxOpen = (el) => {
    if (el.dataset.kind === 'task') T.openTask(el.dataset.id);
    else T.go('notes.html?id=' + encodeURIComponent(el.dataset.id));
  };

  T.views.inbox = { title: 'Inbox', render: render };
})();
