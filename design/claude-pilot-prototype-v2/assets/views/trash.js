/* Trash: deleted app-owned objects with their context. Jira issues and pull requests are never here. */
(function () {
  'use strict';
  const T = window.Thread;
  const TM = T.time;
  const ui = T.ui;
  const esc = T.esc;
  const icon = T.icon;
  const store = T.store;

  const KIND = { task: ['check', 'Task'], note: ['note', 'Note'], topic: ['folder', 'Topic'] };

  function title(e) {
    if (e.kind === 'topic') return e.item.name;
    return e.item.title || 'Untitled note';
  }

  function render() {
    const list = T.state().trash;
    const head = T.pageHead('Trash', 'Deleted tasks, notes and topics. Restore puts each one back where it was, with its links and reminders.',
      list.length ? '<button type="button" class="btn btn-ghost btn-quiet-danger" data-act="emptyTrash">' + icon('trash') + 'Empty trash</button>' : '');
    const note = '<p class="faint" style="font-size:12.5px;margin-top:16px;display:flex;gap:8px;align-items:center">' + icon('lock') + 'Jira issues and pull requests are read-only, so they are never deleted here. Unlinking one from a topic only removes the link.</p>';
    if (!list.length) return head + ui.empty('Trash is empty', 'When you delete a task, note or topic, it waits here until you restore it or empty the trash.', '') + note;
    return head + '<ul class="trash-list" aria-label="Deleted items">' + list.map((e) => {
      const k = KIND[e.kind];
      const rems = (e.reminders || []).length;
      return '<li class="trow-trash"><span class="tile-ic" aria-hidden="true">' + icon(k[0]) + '</span>' +
        '<div><div class="t"><span class="vh">' + k[1] + ': </span>' + esc(title(e)) + '</div>' +
          '<div class="s"><span>' + k[1] + '</span><span>' + esc(e.context) + '</span><span>Deleted ' + esc(TM.fmtStamp(e.deletedAt)) + '</span>' + (rems ? '<span>' + icon('bell') + rems + ' reminder' + (rems === 1 ? '' : 's') + ' kept</span>' : '') + '</div></div>' +
        '<button type="button" class="btn btn-sm" data-act="restoreItem" data-id="' + e.id + '" aria-label="Restore ' + esc(title(e)) + '">' + icon('undo') + 'Restore</button></li>';
    }).join('') + '</ul>' + note;
  }

  T.act.restoreItem = (el) => T.restore(el.dataset.id);
  T.act.emptyTrash = () => {
    const n = T.state().trash.length;
    T.dialog('confirmDlg', {
      label: 'emptyTrH',
      render: () => '<div class="dlg"><div class="dlg-head"><h2 id="emptyTrH">Empty the trash?</h2></div>' +
        '<div class="dlg-body"><p class="muted">' + n + ' item' + (n === 1 ? ' is' : 's are') + ' deleted for good, with their reminders. This cannot be undone.</p></div>' +
        '<div class="dlg-foot"><button type="button" class="btn" data-act="closeDlg">Cancel</button><button type="button" class="btn btn-danger" data-act="confirmEmptyTrash">' + icon('trash') + 'Delete ' + n + ' for good</button></div></div>',
      focus: '.btn'
    });
  };
  T.act.confirmEmptyTrash = () => {
    T.closeDialog('confirmDlg');
    store.update((s) => { s.trash = []; });
    T.toast('Trash emptied');
  };

  T.views.trash = { title: 'Trash', render: render };
})();
