/* Task details as a routed page: the narrow-screen view, and a direct link on desktop. */
(function () {
  'use strict';
  const T = window.Thread;
  const S = T.sel;
  const ui = T.ui;
  const esc = T.esc;
  const icon = T.icon;

  function back(k) {
    const t = k ? S.topicById(k.topicId) : null;
    if (t) return ['topic.html?id=' + encodeURIComponent(t.id), t.name];
    if (k && k.inbox) return ['inbox.html', 'Inbox'];
    return ['today.html', 'Today'];
  }

  function render() {
    const id = T.param('id');
    const k = S.taskById(id);
    if (!k) {
      const trashed = T.state().trash.find((x) => x.kind === 'task' && x.item.id === id);
      return '<a class="backlink" href="today.html">' + icon('chevronLeft') + 'Today</a>' +
        (trashed ? ui.empty('This task is in Trash', 'Restore it from Trash to bring it back with its topic and reminder.', '<a class="btn" href="trash.html">' + icon('trash') + 'Open Trash</a>')
          : ui.empty('Task not found', 'It may have been deleted on another device.', '<a class="btn" href="today.html">Back to Today</a>'));
    }
    document.title = k.title + ' | Thread v2 prototype';
    const b = back(k);
    return '<a class="backlink" href="' + b[0] + '">' + icon('chevronLeft') + esc(b[1]) + '</a>' +
      '<div class="task-page"><h1 class="vh">Task</h1>' + ui.taskForm(k) + '</div>';
  }

  T.views.task = { title: 'Task', render: render };
})();
