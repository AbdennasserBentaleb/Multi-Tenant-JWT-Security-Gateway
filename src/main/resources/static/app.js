/**
 * Multi-Tenant JWT Security Gateway - Frontend Logic
 * Uses Vanilla JS to demonstrate robust API integration and JWT management.
 */

// ───────────────────────────────────────────────────────────
// State Management
// ───────────────────────────────────────────────────────────
const state = {
    token: null,
    decodedToken: null,
    username: null
};

// ───────────────────────────────────────────────────────────
// DOM Elements
// ───────────────────────────────────────────────────────────
const els = {
    views: {
        login: document.getElementById('login-view'),
        dashboard: document.getElementById('dashboard-view')
    },
    auth: {
        status: document.getElementById('auth-status'),
        badge: document.getElementById('current-user-badge'),
        logoutBtn: document.getElementById('logout-btn')
    },
    login: {
        form: document.getElementById('login-form'),
        usernameIn: document.getElementById('username'),
        passwordIn: document.getElementById('password'),
        btn: document.getElementById('login-btn'),
        loader: document.getElementById('login-loader'),
        error: document.getElementById('login-error')
    },
    dashboard: {
        jwtDisplay: document.getElementById('jwt-display'),
        dataError: document.getElementById('data-error'),
        refreshBtn: document.getElementById('refresh-btn')
    },
    table: {
        body: document.getElementById('products-table-body'),
        emptyState: document.getElementById('empty-state'),
        loadingState: document.getElementById('loading-state')
    },
    create: {
        form: document.getElementById('create-product-form'),
        nameIn: document.getElementById('product-name'),
        priceIn: document.getElementById('product-price'),
        btn: document.getElementById('create-btn'),
        loader: document.getElementById('create-loader')
    },
    toast: {
        container: document.getElementById('toast'),
        message: document.getElementById('toast-message')
    }
};

// ───────────────────────────────────────────────────────────
// Initialization
// ───────────────────────────────────────────────────────────
document.addEventListener('DOMContentLoaded', () => {
    // Check for existing session (e.g., page reload)
    const savedToken = sessionStorage.getItem('jwt_token');
    const savedUsername = sessionStorage.getItem('jwt_username');

    if (savedToken && savedUsername) {
        try {
            state.token = savedToken;
            state.username = savedUsername;
            state.decodedToken = parseJwt(savedToken);

            // Check expiry
            if (state.decodedToken.exp * 1000 < Date.now()) {
                throw new Error("Token expired");
            }

            showDashboard();
        } catch (e) {
            clearSession();
            showLogin();
        }
    } else {
        showLogin();
    }

    attachEventListeners();
});

function attachEventListeners() {
    els.login.form.addEventListener('submit', handleLogin);
    els.auth.logoutBtn.addEventListener('click', handleLogout);
    els.create.form.addEventListener('submit', handleCreateProduct);
    els.dashboard.refreshBtn.addEventListener('click', loadProducts);
}

// ───────────────────────────────────────────────────────────
// UI Transitions
// ───────────────────────────────────────────────────────────
function showLogin() {
    els.views.dashboard.classList.add('hidden');
    els.auth.status.classList.add('hidden');
    els.views.login.classList.remove('hidden');

    // Clear potentially lingering errors
    hideError(els.login.error);
    hideError(els.dashboard.dataError);
}

function showDashboard() {
    els.views.login.classList.add('hidden');
    els.views.dashboard.classList.remove('hidden');
    els.auth.status.classList.remove('hidden');

    // Update headers and JWT display
    els.auth.badge.textContent = state.username;

    // Pretty-print JWT payload
    els.dashboard.jwtDisplay.textContent = JSON.stringify(state.decodedToken, null, 2);

    // Load initial data
    loadProducts();
}

function updateLoadingState(elementGroup, isLoading) {
    if (isLoading) {
        elementGroup.btn.disabled = true;
        elementGroup.btn.querySelector('span').classList.add('hidden');
        elementGroup.loader.classList.remove('hidden');
    } else {
        elementGroup.btn.disabled = false;
        elementGroup.btn.querySelector('span').classList.remove('hidden');
        elementGroup.loader.classList.add('hidden');
    }
}

function showError(element, message) {
    element.textContent = message;
    element.classList.remove('hidden');
}

function hideError(element) {
    element.classList.add('hidden');
    element.textContent = '';
}

let toastTimeout;
function showToast(message) {
    els.toast.message.textContent = message;
    els.toast.container.classList.remove('hidden');

    clearTimeout(toastTimeout);
    toastTimeout = setTimeout(() => {
        els.toast.container.classList.add('hidden');
    }, 3000);
}

// ───────────────────────────────────────────────────────────
// Authentication
// ───────────────────────────────────────────────────────────
async function handleLogin(e) {
    e.preventDefault();
    hideError(els.login.error);
    updateLoadingState(els.login, true);

    const username = els.login.usernameIn.value;
    const password = els.login.passwordIn.value;

    try {
        const token = await fetchKeycloakToken(username, password);

        state.token = token;
        state.username = username;
        state.decodedToken = parseJwt(token);

        sessionStorage.setItem('jwt_token', token);
        sessionStorage.setItem('jwt_username', username);

        showDashboard();
    } catch (err) {
        console.error('Login Failed:', err);
        showError(els.login.error, err.message || 'Authentication failed. Please check credentials.');
    } finally {
        updateLoadingState(els.login, false);
    }
}

function handleLogout() {
    clearSession();
    showLogin();
}

function clearSession() {
    state.token = null;
    state.username = null;
    state.decodedToken = null;
    sessionStorage.removeItem('jwt_token');
    sessionStorage.removeItem('jwt_username');
}

async function fetchKeycloakToken(username, password) {
    const params = new URLSearchParams();
    params.append('grant_type', 'password');
    params.append('client_id', window.APP_CONFIG.clientId);
    params.append('username', username);
    params.append('password', password);

    const response = await fetch(window.APP_CONFIG.keycloakUrl, {
        method: 'POST',
        headers: {
            'Content-Type': 'application/x-www-form-urlencoded'
        },
        body: params
    });

    const data = await response.json();

    if (!response.ok) {
        throw new Error(data.error_description || 'Invalid credentials');
    }

    return data.access_token;
}

// ───────────────────────────────────────────────────────────
// API Interactions (Resource Server)
// ───────────────────────────────────────────────────────────

async function loadProducts() {
    hideError(els.dashboard.dataError);
    els.table.emptyState.classList.add('hidden');
    els.table.body.innerHTML = '';
    els.table.loadingState.classList.remove('hidden');

    try {
        const response = await fetchAPI(window.APP_CONFIG.apiUrl, 'GET');
        renderTable(response.content || response); // Handle both pageable and list structures
    } catch (err) {
        console.error('Failed to load products:', err);
        showError(els.dashboard.dataError, 'Failed to connect to the Gateway API. Ensure backend is running.');

        if (err.status === 401) {
            handleLogout();
        }
    } finally {
        els.table.loadingState.classList.add('hidden');
    }
}

async function handleCreateProduct(e) {
    e.preventDefault();
    hideError(els.dashboard.dataError);
    updateLoadingState(els.create, true);

    const payload = {
        name: els.create.nameIn.value.trim(),
        price: parseFloat(els.create.priceIn.value)
    };

    try {
        await fetchAPI(window.APP_CONFIG.apiUrl, 'POST', payload);
        showToast('Product created successfully securely bound to your tenant.');
        els.create.form.reset();
        await loadProducts();
    } catch (err) {
        console.error('Failed to create product:', err);
        showError(els.dashboard.dataError, err.message || 'Failed to create product.');
    } finally {
        updateLoadingState(els.create, false);
    }
}

async function deleteProduct(id) {
    if (!confirm('Are you sure you want to delete this product?')) return;

    try {
        await fetchAPI(`${window.APP_CONFIG.apiUrl}/${id}`, 'DELETE');
        showToast('Product deleted.');
        await loadProducts();
    } catch (err) {
        console.error('Failed to delete product:', err);
        showError(els.dashboard.dataError, 'Deletion failed. It may not belong to your tenant.');
    }
}
window.deleteProduct = deleteProduct; // Expose to global scope for inline onclick handlers

// Core Fetch Wrapper
async function fetchAPI(url, method, body = null) {
    const headers = {
        'Authorization': `Bearer ${state.token}`,
        'Accept': 'application/json'
    };

    if (body) {
        headers['Content-Type'] = 'application/json';
    }

    const response = await fetch(url, {
        method,
        headers,
        cache: 'no-store',
        body: body ? JSON.stringify(body) : null
    });

    if (response.status === 204) {
        return null;
    }

    if (!response.ok) {
        let errorMsg = `HTTP Error: ${response.status}`;
        try {
            const errData = await response.json();
            errorMsg = errData.detail || errData.message || errorMsg;
        } catch (e) { }

        const error = new Error(errorMsg);
        error.status = response.status;
        throw error;
    }

    return response.json();
}

// ───────────────────────────────────────────────────────────
// View Rendering
// ───────────────────────────────────────────────────────────
function renderTable(products) {
    els.table.body.innerHTML = '';

    if (!products || products.length === 0) {
        els.table.emptyState.classList.remove('hidden');
        return;
    }

    products.forEach(product => {
        const tr = document.createElement('tr');
        tr.innerHTML = `
            <td class="font-mono text-muted" style="font-size: 0.8rem">${product.id.split('-')[0]}...</td>
            <td class="font-medium">${escapeHtml(product.name)}</td>
            <td>$${product.price.toFixed(2)}</td>
            <td class="text-right">
                <button class="btn btn-danger-outline" onclick="deleteProduct('${product.id}')">Delete</button>
            </td>
        `;
        els.table.body.appendChild(tr);
    });
}

// ───────────────────────────────────────────────────────────
// Utilities
// ───────────────────────────────────────────────────────────
function parseJwt(token) {
    try {
        const base64Url = token.split('.')[1];
        const base64 = base64Url.replace(/-/g, '+').replace(/_/g, '/');
        const jsonPayload = decodeURIComponent(atob(base64).split('').map(function (c) {
            return '%' + ('00' + c.charCodeAt(0).toString(16)).slice(-2);
        }).join(''));
        return JSON.parse(jsonPayload);
    } catch (e) {
        throw new Error("Invalid JWT token format");
    }
}

function escapeHtml(unsafe) {
    return (unsafe || '').toString()
        .replace(/&/g, "&amp;")
        .replace(/</g, "&lt;")
        .replace(/>/g, "&gt;")
        .replace(/"/g, "&quot;")
        .replace(/'/g, "&#039;");
}
