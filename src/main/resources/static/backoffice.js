(() => {
    'use strict';

    const { $, $$, escapeHtml, setContent, emptyState, formatMoment, formatRange, statusBadge, createSession, sessionKeys } = window.Campus;
    const state = {
        user: null,
        accessError: '',
        shopId: '',
        managedId: '',
        staffView: 'workbench',
        batchPage: 1,
        batchId: '',
        redemptionPage: 1,
        detailRequest: null,
        detailTimer: null,
        pageRequest: null,
        progressTimer: null
    };
    const adminWindows = '/backoffice/admin/windows';
    const { api, restoreAccount } = createSession(state, {
        storageKey: sessionKeys.backoffice,
        accountPath: '/backoffice/access',
        signIn,
        onChange() {
            state.accessError = '';
            if (!state.user) {
                state.shopId = '';
                state.managedId = '';
                $$('main form').forEach(form => form.reset());
                $$('.feedback').forEach(node => setFeedback(node, ''));
                ['#staffBatches', '#staffList', '#windowsNotice'].forEach(selector => setContent($(selector), ''));
            }
            renderPage();
        },
        onLookupError(error) {
            state.accessError = error.message;
            renderPage();
        }
    });

    function signIn() {
        const params = new URLSearchParams({ portal: 'backoffice' });
        if (location.hash) params.set('next', location.hash);
        location.assign(`/login.html?${params}`);
    }

    function setFeedback(node, message, error = false) {
        node.textContent = message;
        node.classList.toggle('error', error);
    }

    function shopOptions(shops) {
        return shops.map(shop => `<option value="${escapeHtml(shop.id)}">${escapeHtml(shop.name)}</option>`).join('');
    }

    function renderPage() {
        state.pageRequest?.abort();
        clearTimeout(state.progressTimer);
        state.pageRequest = new AbortController();
        const { signal } = state.pageRequest;
        const { user, accessError } = state;
        const isStaff = Boolean(user?.windows.length);
        const isAdmin = Boolean(user?.administrator);
        const adminPage = isAdmin && (!isStaff || location.hash === '#windows');
        const staffPage = isStaff && !adminPage;
        const notice = $('#accessNotice');
        const role = $('#backofficeRole');
        role.hidden = !(isAdmin || isStaff) || Boolean(accessError);
        role.textContent = isAdmin && isStaff ? '管理员 / 窗口人员' : isAdmin ? '管理员' : '窗口人员';
        $('#backofficeWorkspace').hidden = role.hidden;
        $('#staffLink').hidden = !isStaff;
        $('#adminLink').hidden = !isAdmin;
        for (const [selector, active] of [['#staffLink', staffPage], ['#adminLink', adminPage]]) {
            if (active) $(selector).setAttribute('aria-current', 'page');
            else $(selector).removeAttribute('aria-current');
        }
        $('#staffPanel').hidden = !staffPage || Boolean(accessError);
        if ((!staffPage || accessError) && $('#batchDialog').open) $('#batchDialog').close();
        $('#adminPanel').hidden = !adminPage || Boolean(accessError);

        if (accessError) {
            setContent(notice, emptyState('后台权限加载失败', accessError,
                '<button type="button" id="retryAccess">重试</button>'));
        } else if (!user) {
            setContent(notice, state.token ? emptyState('正在确认后台权限') :
                emptyState('登录食堂运营后台', '请使用窗口人员或管理员账号。',
                    '<button type="button" id="loginAccess">登录</button>'));
        } else if (!isStaff && !isAdmin) {
            setContent(notice, emptyState('此账号暂无后台权限', '窗口人员请联系管理员绑定食堂窗口。'));
        } else {
            setContent(notice, '');
            if (staffPage) {
                if (!user.windows.some(shop => shop.id === state.shopId)) {
                    state.shopId = user.windows[0].id;
                    setContent($('#staffBatches'), '');
                }
                setContent($('#mealShop'), shopOptions(user.windows));
                $('#mealShop').value = state.shopId;
                const showStats = state.staffView === 'stats';
                $('#workbenchTab').setAttribute('aria-pressed', String(!showStats));
                $('#statsTab').setAttribute('aria-pressed', String(showStats));
                $('#staffWorkbench').hidden = showStats;
                $('#operationsReport').hidden = !showStats;
                if (showStats) loadStats(signal);
                else loadStaffProgress(signal);
            }
            if (adminPage) loadWindows(signal);
        }
    }

    async function loadStaffProgress(signal) {
        const list = $('#staffBatches');
        list.setAttribute('aria-busy', 'true');
        try {
            const { items: batches, hasNext } = await api(`/staff/shops/${state.shopId}/meal-batches?page=${state.batchPage}`, { signal });
            if (signal.aborted) return;
            setContent(list, batches.length ? `<div class="progress-list">${batches.map(batch => `
                <article class="progress-row">
                    <div>
                        <h3>${escapeHtml(batch.title)}</h3>${statusBadge(batch.status)}
                        <p>取餐 ${formatRange(batch.pickupBeginTime, batch.pickupEndTime)}</p>
                        <button type="button" class="secondary-button detail-link" data-batch-id="${escapeHtml(batch.id)}">查看详情</button>
                    </div>
                    <dl><div><dt>剩余</dt><dd>${Number(batch.remaining)}</dd></div>
                        <div><dt>待取餐</dt><dd>${Number(batch.awaitingPickup)}</dd></div>
                        <div><dt>已核销</dt><dd>${Number(batch.redeemed)}</dd></div></dl>
                </article>`).join('')}</div>` : emptyState(state.batchPage === 1 ? '当前窗口还没有发布批次' : '此页暂无批次'));
            updatePages('#batchPages', state.batchPage, hasNext);
            if (batches.some(batch => ['PUBLISHING', 'CLOSING'].includes(batch.status))) {
                state.progressTimer = setTimeout(() => {
                    if (!signal.aborted) loadStaffProgress(signal);
                }, 2200);
            }
        } catch (error) {
            if (!signal.aborted) {
                setContent(list, emptyState('批次进度加载失败', error.message));
                updatePages('#batchPages', state.batchPage, false);
            }
        } finally {
            if (!signal.aborted) list.setAttribute('aria-busy', 'false');
        }
    }

    function updatePages(selector, page, hasNext) {
        const nav = $(selector);
        nav.hidden = page === 1 && !hasNext;
        $('span', nav).textContent = `第 ${page} 页`;
        const [previous, next] = nav.querySelectorAll('button');
        previous.disabled = page === 1;
        next.disabled = !hasNext;
    }

    function metrics(values) {
        return `<dl class="operation-metrics">${values.map(([label, value]) =>
            `<div><dt>${label}</dt><dd>${value}</dd></div>`).join('')}</dl>`;
    }

    function openBatch(id) {
        state.batchId = id;
        state.redemptionPage = 1;
        const dialog = $('#batchDialog');
        setContent($('#batchDetail'), '<p>正在加载批次…</p>');
        setContent($('#redemptionLog'), '');
        $('#redemptionPages').hidden = true;
        setFeedback($('#closeFeedback'), '');
        if (!dialog.open) dialog.showModal();
        loadBatchDetail();
    }

    async function loadBatchDetail() {
        state.detailRequest?.abort();
        clearTimeout(state.detailTimer);
        state.detailRequest = new AbortController();
        const { signal } = state.detailRequest;
        const { shopId, batchId, redemptionPage } = state;
        const root = `/staff/shops/${shopId}`;
        const [detail, redemptions] = await Promise.allSettled([
            api(`${root}/meal-batches/${batchId}`, { signal }),
            api(`${root}/redemptions?batchId=${batchId}&page=${redemptionPage}`, { signal })
        ]);
        if (signal.aborted) return;
        const detailNode = $('#batchDetail');
        const log = $('#redemptionLog');
        if (detail.status === 'fulfilled') {
            const batch = detail.value;
            setContent(detailNode, `<div class="batch-detail-heading"><h3>${escapeHtml(batch.title)}</h3>${statusBadge(batch.status)}</div>
                <div class="batch-dates"><p>预约时段 ${formatRange(batch.beginTime, batch.endTime)}</p>
                <p>取餐时段 ${formatRange(batch.pickupBeginTime, batch.pickupEndTime)}</p>
                ${batch.bookingClosedAt ? `<p>提前关单 ${formatMoment(batch.bookingClosedAt)}</p>` : ''}</div>
                ${metrics([['投放', batch.portions], ['未分配', batch.remaining], ['待取餐', batch.awaitingPickup],
                    ['已核销', batch.redeemed], ['取消次数', batch.cancelled], ['已逾期', batch.expired]])}
                ${batch.canClose ? '<button type="button" class="secondary-button close-booking" id="closeBooking">提前关单</button>' : ''}`);
            if (batch.status === 'CLOSING') state.detailTimer = setTimeout(loadBatchDetail, 2000);
        } else {
            setContent(detailNode, emptyState('批次详情加载失败', detail.reason.message));
        }
        if (redemptions.status === 'fulfilled') {
            const { items, hasNext } = redemptions.value;
            setContent(log, items.length ? `<ul class="redemption-list">${items.map(item => `<li>
                <strong>${escapeHtml(item.orderId)}</strong><span>${formatMoment(item.redeemedAt)}</span>
                <span>${escapeHtml(item.staffName || '未记录人员')}</span></li>`).join('')}</ul>` : emptyState('此批次暂无核销记录'));
            updatePages('#redemptionPages', redemptionPage, hasNext);
        } else {
            setContent(log, emptyState('核销记录加载失败', redemptions.reason.message));
            updatePages('#redemptionPages', redemptionPage, false);
        }
    }

    async function closeBooking(button) {
        if (!window.confirm('停止接受新预约？已受理的预约仍可按原时段取餐。')) return;
        const { shopId, batchId, token } = state;
        const active = () => $('#batchDialog').open && state.batchId === batchId && state.shopId === shopId && state.token === token;
        button.disabled = true;
        const feedback = $('#closeFeedback');
        setFeedback(feedback, '');
        try {
            const { status } = await api(`/staff/shops/${shopId}/meal-batches/${batchId}/close-booking`, { method: 'POST' });
            if (state.shopId === shopId && state.token === token) renderPage();
            if (!active()) return;
            setFeedback(feedback, status === 'CLOSING' ? '关单请求已保存，正在停止接单。' : '已停止接收新预约。');
            loadBatchDetail();
        } catch (error) {
            if (active()) setFeedback(feedback, error.message, true);
        } finally {
            button.disabled = false;
        }
    }

    async function loadStats(signal) {
        const panel = $('#mealStats');
        setContent(panel, '<p>正在汇总窗口数据…</p>');
        const dates = new URLSearchParams([...new FormData($('#mealStatsForm'))].filter(([, value]) => value));
        try {
            const rows = await api(`/staff/shops/${state.shopId}/meal-stats?${dates}`, { signal });
            if (signal.aborted) return;
            if (!rows.length) {
                setContent(panel, emptyState('这段时间没有取餐批次', '默认查询近 7 天，可调整日期范围。'));
                return;
            }
            const sum = field => rows.reduce((total, row) => total + row[field], 0);
            const rate = (redeemed, expired) => redeemed + expired ? `${(100 * redeemed / (redeemed + expired)).toFixed(1)}%` : '—';
            setContent(panel, `${metrics([['投放份数', sum('portions')], ['已核销', sum('redeemed')], ['已逾期', sum('expired')],
                    ['取餐率', rate(sum('redeemed'), sum('expired'))]])}
                <p class="report-note">按批次取餐开始日期统计。取餐率 = 已核销 ÷（已核销 + 已逾期）。</p>
                <div class="report-table" tabindex="0" role="region" aria-label="每日取餐统计"><table>
                    <thead><tr><th scope="col">日期</th><th scope="col">批次</th><th scope="col">投放</th><th scope="col">未分配</th>
                    <th scope="col">预约笔数</th><th scope="col">待取餐</th><th scope="col">已核销</th><th scope="col">取消次数</th>
                    <th scope="col">已逾期</th><th scope="col">取餐率</th></tr></thead>
                    <tbody>${rows.map(row => `<tr><th scope="row">${escapeHtml(row.date)}</th>
                        <td>${row.batches}</td><td>${row.portions}</td><td>${row.remaining}</td>
                        <td>${row.awaitingPickup + row.redeemed + row.cancelled + row.expired}</td><td>${row.awaitingPickup}</td>
                        <td>${row.redeemed}</td><td>${row.cancelled}</td><td>${row.expired}</td><td>${rate(row.redeemed, row.expired)}</td></tr>`).join('')}</tbody>
                </table></div>`);
        } catch (error) {
            if (!signal.aborted) setContent(panel, emptyState('统计加载失败', error.message));
        }
    }

    async function submitStaffForm(event) {
        event.preventDefault();
        const form = event.currentTarget;
        const publish = form.id === 'publishMeal';
        const fields = Object.fromEntries(new FormData(form));
        const { shopId, token } = state;
        const action = publish ? 'meal-batches' : `meal-reservations/${fields.orderId}/redeem`;
        const payload = publish ? { ...fields, stock: Number(fields.stock) } : { pickupCode: fields.pickupCode };
        const button = $('button[type="submit"]', form);
        const feedback = $('.feedback', form);
        button.disabled = true;
        setFeedback(feedback, '');
        try {
            await api(`/staff/shops/${shopId}/${action}`, { method: 'POST', body: JSON.stringify(payload) });
            if (token !== state.token || shopId !== state.shopId) return;
            form.reset();
            if (publish) state.batchPage = 1;
            setFeedback(feedback, publish ? '批次已提交，发布后将在学生端展示。' : '已核销，可以交餐。');
            renderPage();
        } catch (error) {
            if (token === state.token && shopId === state.shopId) setFeedback(feedback, error.message, true);
        } finally {
            button.disabled = false;
        }
    }

    async function loadWindows(signal) {
        const notice = $('#windowsNotice');
        try {
            const windows = await api(adminWindows, { signal });
            if (signal.aborted) return;
            const selected = windows.find(shop => shop.id === state.managedId) || windows[0];
            $('#windowStaff').hidden = !selected;
            setContent(notice, selected ? '' : emptyState('还没有食堂窗口', '新增窗口后即可绑定人员。'));
            if (!selected) return;
            state.managedId = selected.id;
            setContent($('#managedWindow'), shopOptions(windows));
            $('#managedWindow').value = selected.id;
            $('#windowAddress').textContent = selected.address;
            loadWindowStaff(selected.id, signal);
        } catch (error) {
            if (signal.aborted) return;
            $('#windowStaff').hidden = true;
            setContent(notice, emptyState('窗口加载失败', error.message,
                '<button type="button" id="retryWindows">重试</button>'));
        }
    }

    async function loadWindowStaff(shopId, signal) {
        const list = $('#staffList');
        list.setAttribute('aria-busy', 'true');
        try {
            const staff = await api(`${adminWindows}/${shopId}/staff`, { signal });
            if (signal.aborted) return;
            setContent(list, staff.length ? `<ul class="window-staff-list">${staff.map(user => `
                <li><span><strong>${escapeHtml(user.nickName || '窗口人员')}</strong><small>用户 ID ${escapeHtml(user.id)}</small></span>
                <button type="button" class="secondary-button" data-unbind="${escapeHtml(user.id)}">解绑</button></li>`).join('')}</ul>`
                : emptyState('此窗口还没有绑定人员'));
        } catch (error) {
            if (!signal.aborted) setContent(list, emptyState('窗口人员加载失败', error.message,
                '<button type="button" id="retryStaff">重试</button>'));
        } finally {
            if (!signal.aborted) list.setAttribute('aria-busy', 'false');
        }
    }

    async function submitManagementForm(event) {
        event.preventDefault();
        const form = event.currentTarget;
        const creating = form.id === 'createWindow';
        const { token, managedId } = state;
        const button = $('button[type="submit"]', form);
        const feedback = $('.feedback', form);
        const path = creating ? adminWindows : `${adminWindows}/${managedId}/staff`;
        button.disabled = true;
        setFeedback(feedback, '');
        try {
            const body = JSON.stringify(Object.fromEntries(new FormData(form)));
            const window = await api(path, { method: 'POST', body });
            if (token !== state.token) return;
            if (creating) {
                state.managedId = window.id;
                setContent($('#staffList'), '');
            }
            if (creating || managedId === state.managedId) {
                form.reset();
                setFeedback(feedback, creating ? '窗口已新增。' : '窗口人员已绑定。');
            }
            await restoreAccount();
        } catch (error) {
            if (token === state.token && (creating || managedId === state.managedId)) {
                setFeedback(feedback, error.message, true);
            }
        } finally {
            button.disabled = false;
        }
    }

    async function unbindStaff(button) {
        if (!window.confirm('解绑后，该人员将失去此窗口的发布和核销权限。确定解绑？')) return;
        const { token, managedId } = state;
        const feedback = $('#staffFeedback');
        button.disabled = true;
        setFeedback(feedback, '');
        try {
            await api(`${adminWindows}/${managedId}/staff/${button.dataset.unbind}`, { method: 'DELETE' });
            if (token !== state.token) return;
            if (managedId === state.managedId) setFeedback(feedback, '窗口人员已解绑。');
            await restoreAccount();
        } catch (error) {
            if (token === state.token && managedId === state.managedId) setFeedback(feedback, error.message, true);
        } finally {
            button.disabled = false;
        }
    }

    window.addEventListener('hashchange', renderPage);
    $('#refreshStaff').addEventListener('click', renderPage);
    $('#mealShop').addEventListener('change', event => {
        state.shopId = event.target.value;
        state.batchPage = 1;
        if ($('#batchDialog').open) $('#batchDialog').close();
        $$('.staff-form').forEach(form => {
            form.reset();
            setFeedback($('.feedback', form), '');
        });
        setContent($('#staffBatches'), '');
        renderPage();
    });
    $('#managedWindow').addEventListener('change', event => {
        state.managedId = event.target.value;
        $('#bindStaff').reset();
        setFeedback($('#staffFeedback'), '');
        setContent($('#staffList'), '');
        renderPage();
    });
    $('#publishMeal').addEventListener('submit', submitStaffForm);
    $('#redeemMeal').addEventListener('submit', submitStaffForm);
    $('#createWindow').addEventListener('submit', submitManagementForm);
    $('#bindStaff').addEventListener('submit', submitManagementForm);
    for (const [selector, view] of [['#workbenchTab', 'workbench'], ['#statsTab', 'stats']]) {
        $(selector).addEventListener('click', () => {
            state.staffView = view;
            renderPage();
        });
    }
    $('#mealStatsForm').addEventListener('submit', event => {
        event.preventDefault();
        renderPage();
    });
    $('#batchDialog').addEventListener('close', () => {
        state.detailRequest?.abort();
        clearTimeout(state.detailTimer);
        state.batchId = '';
    });
    $('#batchDialog').addEventListener('click', event => {
        const button = event.target.closest('button');
        if (!button) return;
        if (button.id === 'closeBooking') closeBooking(button);
        if (button.dataset.redemptionPage) {
            state.redemptionPage += Number(button.dataset.redemptionPage);
            loadBatchDetail();
        }
    });
    $('#main').addEventListener('click', event => {
        const button = event.target.closest('button');
        if (!button) return;
        if (button.id === 'loginAccess') signIn();
        if (button.id === 'retryAccess') restoreAccount();
        if (button.id === 'retryWindows' || button.id === 'retryStaff') renderPage();
        if (button.dataset.unbind) unbindStaff(button);
        if (button.dataset.batchId) openBatch(button.dataset.batchId);
        if (button.dataset.batchPage) {
            state.batchPage += Number(button.dataset.batchPage);
            renderPage();
        }
    });
    renderPage();
    restoreAccount();
})();
