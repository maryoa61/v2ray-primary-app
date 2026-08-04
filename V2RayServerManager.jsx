import React, { useState, useEffect } from 'react';

/**
 * V2RayServerManager - A highly polished React component for managing
 * multiple V2Ray server profiles (Add, Edit, Delete) with localStorage persistence.
 * Styled with a cyber-industrial dark aesthetic matching the V2Ray Dan theme.
 */
export default function V2RayServerManager() {
  // State for servers list
  const [servers, setServers] = useState([]);
  
  // State for active / selected server
  const [activeServerId, setActiveServerId] = useState(null);
  
  // State for the editor form/modal
  const [isEditing, setIsEditing] = useState(false);
  const [editingServer, setEditingServer] = useState(null);
  const [showFormModal, setShowFormModal] = useState(false);
  
  // Form field states
  const [name, setName] = useState('');
  const [type, setType] = useState('VLESS'); // VLESS, VMESS, SHADOWSOCKS, TROJAN
  const [address, setAddress] = useState('');
  const [port, setPort] = useState('443');
  const [uuid, setUuid] = useState('');
  const [network, setNetwork] = useState('ws'); // ws, tcp, grpc
  const [path, setPath] = useState('');
  const [tls, setTls] = useState(true);
  const [sni, setSni] = useState('');

  // Search and filter states
  const [searchQuery, setSearchQuery] = useState('');
  const [filterType, setFilterType] = useState('ALL');

  // Load from localStorage on mount
  useEffect(() => {
    try {
      const stored = localStorage.getItem('v2ray_server_profiles');
      if (stored) {
        const parsed = JSON.parse(stored);
        setServers(parsed);
        if (parsed.length > 0) {
          setActiveServerId(parsed[0].id);
        }
      } else {
        // Seed initial mock data for demo / empty state friendly guide
        const initialSeed = [
          {
            id: 'seed-1',
            name: 'Germany Gate Premium',
            type: 'VLESS',
            address: 'de1.securenode.com',
            port: 443,
            uuid: '7a6e12e1-419b-4ff2-a4e1-2357be8159bb',
            network: 'ws',
            path: '/vless-ws',
            tls: true,
            sni: 'de1.securenode.com',
            latency: 84
          },
          {
            id: 'seed-2',
            name: 'Singapore High-Speed VMess',
            type: 'VMESS',
            address: 'sg-fast.gateway.net',
            port: 8080,
            uuid: 'fa85b1a3-61e8-4672-bdf1-331698faee12',
            network: 'grpc',
            path: 'VmessGrpcService',
            tls: false,
            sni: '',
            latency: 142
          }
        ];
        setServers(initialSeed);
        setActiveServerId(initialSeed[0].id);
        localStorage.setItem('v2ray_server_profiles', JSON.stringify(initialSeed));
      }
    } catch (e) {
      console.error('Failed to access localStorage:', e);
    }
  }, []);

  // Save to localStorage whenever servers state changes
  const saveServers = (updatedList) => {
    setServers(updatedList);
    try {
      localStorage.setItem('v2ray_server_profiles', JSON.stringify(updatedList));
    } catch (e) {
      console.error('Failed to write to localStorage:', e);
    }
  };

  // Open form for adding a new profile
  const handleAddNew = () => {
    setIsEditing(false);
    setEditingServer(null);
    setName('');
    setType('VLESS');
    setAddress('');
    setPort('443');
    setUuid('');
    setNetwork('ws');
    setPath('');
    setTls(true);
    setSni('');
    setShowFormModal(true);
  };

  // Open form for editing an existing profile
  const handleEdit = (server, e) => {
    e.stopPropagation(); // Avoid triggering list item selection onClick
    setIsEditing(true);
    setEditingServer(server);
    setName(server.name);
    setType(server.type);
    setAddress(server.address);
    setPort(server.port.toString());
    setUuid(server.uuid);
    setNetwork(server.network);
    setPath(server.path || '');
    setTls(server.tls);
    setSni(server.sni || '');
    setShowFormModal(true);
  };

  // Delete a server profile
  const handleDelete = (id, e) => {
    e.stopPropagation(); // Avoid list select onClick
    if (confirm('Are you sure you want to delete this server profile?')) {
      const updatedList = servers.filter(s => s.id !== id);
      saveServers(updatedList);
      if (activeServerId === id) {
        setActiveServerId(updatedList.length > 0 ? updatedList[0].id : null);
      }
    }
  };

  // Handle form Submission (Add / Update)
  const handleSubmit = (e) => {
    e.preventDefault();
    if (!name || !address || !port) {
      alert('Please fill out Name, Host IP, and Port!');
      return;
    }

    const payload = {
      id: isEditing && editingServer ? editingServer.id : 'v2ray-' + Date.now(),
      name: name,
      type: type,
      address: address,
      port: parseInt(port) || 443,
      uuid: uuid,
      network: network,
      path: network === 'tcp' ? '' : path,
      tls: tls,
      sni: tls ? sni : '',
      latency: isEditing && editingServer ? editingServer.latency : null // retain latency if editing
    };

    let updated;
    if (isEditing) {
      updated = servers.map(s => s.id === payload.id ? payload : s);
    } else {
      updated = [...servers, payload];
      setActiveServerId(payload.id); // set newly created as active
    }

    saveServers(updated);
    setShowFormModal(false);
  };

  // Export profiles as JSON file share
  const handleExportJSON = () => {
    const dataStr = "data:text/json;charset=utf-8," + encodeURIComponent(JSON.stringify(servers, null, 2));
    const downloadAnchor = document.createElement('a');
    downloadAnchor.setAttribute("href", dataStr);
    downloadAnchor.setAttribute("download", "v2ray_server_profiles.json");
    document.body.appendChild(downloadAnchor);
    downloadAnchor.click();
    downloadAnchor.remove();
  };

  // Filter & Search computation
  const filteredServers = servers.filter(server => {
    const matchesSearch = server.name.toLowerCase().includes(searchQuery.toLowerCase()) || 
                          server.address.toLowerCase().includes(searchQuery.toLowerCase());
    const matchesType = filterType === 'ALL' || server.type === filterType;
    return matchesSearch && matchesType;
  });

  return (
    <div className="min-h-screen bg-[#0b0f19] text-[#e2e8f0] font-sans antialiased p-4 md:p-8 flex flex-col justify-between">
      
      {/* Container wrapper */}
      <div className="max-w-6xl mx-auto w-full">
        
        {/* Header Block */}
        <header className="flex flex-col md:flex-row justify-between items-start md:items-center mb-8 pb-6 border-b border-slate-800 gap-4">
          <div>
            <div className="text-[#94a3b8] text-[10px] font-bold tracking-widest uppercase mb-1">
              V2RAY CLIENT ENGINE
            </div>
            <h1 className="text-3xl font-extrabold text-white flex items-center gap-2">
              <span className="text-[#39ff14] animate-pulse">●</span> V2Ray Server Gateway Manager
            </h1>
          </div>
          
          <div className="flex gap-3">
            <button
              onClick={handleExportJSON}
              className="px-4 py-2 text-sm bg-slate-800 hover:bg-slate-700 active:bg-slate-900 border border-slate-700 hover:border-slate-600 rounded-lg font-medium transition duration-200 flex items-center gap-2"
            >
              <svg className="w-4 h-4 text-[#00f0ff]" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M4 16v1a3 3 0 003 3h10a3 3 0 003-3v-1m-4-4l-4 4m0 0l-4-4m4 4V4"></path></svg>
              Export Configuration
            </button>
            <button
              onClick={handleAddNew}
              className="px-5 py-2 text-sm bg-[#39ff14] text-[#0b0f19] hover:bg-[#2ecc17] active:scale-95 font-bold rounded-lg transition duration-200 flex items-center gap-2 shadow-[0_0_15px_rgba(57,255,20,0.3)]"
            >
              <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth="3" d="M12 4v16m8-8H4"></path></svg>
              Add Node Server
            </button>
          </div>
        </header>

        {/* Dashboard Grid split config-view and detail-card */}
        <div className="grid grid-cols-1 lg:grid-cols-12 gap-8">
          
          {/* LEFT: Node Selection List (8 cols) */}
          <section className="lg:col-span-7 flex flex-col gap-4">
            
            {/* Search and Filters panel */}
            <div className="bg-[#1e293b]/60 border border-slate-800 rounded-xl p-4 flex flex-col sm:flex-row gap-3">
              <div className="relative flex-1">
                <input
                  type="text"
                  placeholder="Search gate name or host IP..."
                  value={searchQuery}
                  onChange={(e) => setSearchQuery(e.target.value)}
                  className="w-full bg-[#0b0f19] border border-slate-800 focus:border-[#00f0ff] focus:outline-none rounded-lg px-4 py-2 pl-10 text-sm transition"
                />
                <svg className="w-4 h-4 text-slate-500 absolute left-3 top-3" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z"></path></svg>
              </div>

              <div className="flex gap-2">
                {['ALL', 'VLESS', 'VMESS', 'SHADOWSOCKS', 'TROJAN'].map((t) => (
                  <button
                    key={t}
                    onClick={() => setFilterType(t)}
                    className={`px-3 py-2 text-[10px] font-bold rounded-md uppercase tracking-wider transition ${
                      filterType === t 
                        ? 'bg-[#00f0ff] text-[#0b0f19]' 
                        : 'bg-slate-800 hover:bg-slate-700 text-[#94a3b8]'
                    }`}
                  >
                    {t === 'SHADOWSOCKS' ? 'SS' : t}
                  </button>
                ))}
              </div>
            </div>

            {/* List Body */}
            {filteredServers.length === 0 ? (
              <div className="bg-[#1e293b]/30 border-2 border-dashed border-slate-800 rounded-xl p-12 text-center flex flex-col items-center justify-center min-h-[300px]">
                <div className="w-16 h-16 rounded-full bg-slate-800 flex items-center justify-center mb-4 border border-slate-700">
                  <svg className="w-8 h-8 text-[#f59e0b]" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth="1.5" d="M18.364 8.05a9 9 0 00-2.83-4.83m0 0L12 5.05m3.536-1.83L18.364 5m-6.364 3.05a9 9 0 11-8.485 8.485m8.485-8.485L12 5.05"></path></svg>
                </div>
                <h3 className="text-lg font-bold text-white mb-1">No Connection Nodes Found</h3>
                <p className="text-slate-400 text-sm max-w-sm mb-6">
                  No profiles match your search criteria. Create a custom gateway layout to establish connections.
                </p>
                <button
                  onClick={handleAddNew}
                  className="px-4 py-2 text-sm bg-[#39ff14] text-[#0b0f19] font-bold rounded-lg hover:bg-[#2ecc17] transition"
                >
                  Create New Gateway Profile
                </button>
              </div>
            ) : (
              <div className="flex flex-col gap-3 max-h-[600px] overflow-y-auto pr-1">
                {filteredServers.map((server) => {
                  const isSelected = activeServerId === server.id;
                  
                  // Color tag styling
                  let labelColorClass = "bg-[#d946ef]/12 text-[#d946ef]"; // default trojan
                  let labelText = server.type;
                  if (server.type === 'VLESS') {
                    labelColorClass = "bg-[#00f0ff]/10 text-[#00f0ff]";
                  } else if (server.type === 'VMESS') {
                    labelColorClass = "bg-[#39ff14]/10 text-[#39ff14]";
                  } else if (server.type === 'SHADOWSOCKS') {
                    labelColorClass = "bg-[#ff007f]/10 text-[#ff007f]";
                    labelText = 'SS';
                  }

                  return (
                    <div
                      key={server.id}
                      onClick={() => setActiveServerId(server.id)}
                      className={`p-4 rounded-xl cursor-pointer transition border duration-200 flex justify-between items-center ${
                        isSelected 
                          ? 'bg-[#1e293b] border-[#39ff14]' 
                          : 'bg-[#1e293b]/70 border-slate-800/80 hover:border-slate-700'
                      }`}
                    >
                      {/* Name and connection brief info */}
                      <div className="flex items-center gap-4">
                        <div className={`w-12 h-12 rounded-lg flex items-center justify-center font-black text-xs ${labelColorClass}`}>
                          {labelText}
                        </div>
                        <div>
                          <div className="flex items-center gap-2">
                            <h4 className="text-sm font-bold text-white max-w-[200px] sm:max-w-[260px] truncate">
                              {server.name}
                            </h4>
                            {server.tls && (
                              <svg className="w-3.5 h-3.5 text-[#00f0ff]" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M12 15v2m-6 4h12a2 2 0 002-2v-6a2 2 0 00-2-2H6a2 2 0 00-2 2v6a2 2 0 002 2zm10-10V7a4 4 0 00-8 0v4h8z"></path></svg>
                            )}
                          </div>
                          <p className="text-xs text-slate-400 mt-1 truncate max-w-[200px] sm:max-w-xs font-mono">
                            {server.address}:{server.port} • {server.network.toUpperCase()}
                          </p>
                        </div>
                      </div>

                      {/* Ping and configuration action click buttons */}
                      <div className="flex items-center gap-3">
                        {server.latency ? (
                          <div className={`px-2 py-1 bg-[#0b0f19] rounded text-[10px] font-mono font-bold ${
                            server.latency < 100 ? 'text-[#39ff14]' : 'text-[#f59e0b]'
                          }`}>
                            {server.latency}ms
                          </div>
                        ) : (
                          <div className="px-2 py-1 bg-[#0b0f19] rounded text-[10px] font-mono text-slate-600 font-bold">
                            ---
                          </div>
                        )}

                        <div className="flex gap-1">
                          <button
                            onClick={(e) => handleEdit(server, e)}
                            className="p-1.5 hover:bg-slate-800 text-slate-400 hover:text-white rounded transition"
                            title="Edit Gateway Profile"
                          >
                            <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M15.232 5.232l3.536 3.536m-2.036-5.036a2.5 2.5 0 113.536 3.536L6.5 21.036H3v-3.572L16.732 3.732z"></path></svg>
                          </button>
                          <button
                            onClick={(e) => handleDelete(server.id, e)}
                            className="p-1.5 hover:bg-red-950 text-slate-400 hover:text-red-500 rounded transition"
                            title="Delete Gateway Configuration"
                          >
                            <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16"></path></svg>
                          </button>
                        </div>
                      </div>
                    </div>
                  );
                })}
              </div>
            )}
          </section>

          {/* RIGHT: Node Complete Details Panel (5 cols) */}
          <aside className="lg:col-span-5">
            <div className="bg-[#1e293b] border border-slate-800 rounded-xl p-6 sticky top-8">
              <h2 className="text-lg font-bold text-white mb-4 flex items-center gap-2 border-b border-slate-800 pb-3">
                <svg className="w-4 h-4 text-[#39ff14]" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M13 16h-1v-4h-1m1-4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z"></path></svg>
                Gateway Detail Inspector
              </h2>

              {(() => {
                const activeServer = servers.find(s => s.id === activeServerId);
                if (!activeServer) {
                  return (
                    <div className="text-center text-slate-500 py-12">
                      Please select or configure a gateway profile node to inspect structural protocol parameters.
                    </div>
                  );
                }

                return (
                  <div className="space-y-4">
                    <div className="flex justify-between items-center bg-[#0b0f19] p-3 rounded-lg border border-slate-900">
                      <span className="text-xs text-slate-400">Node Profile Name</span>
                      <span className="text-sm font-bold text-white text-right truncate max-w-[200px]" title={activeServer.name}>
                        {activeServer.name}
                      </span>
                    </div>

                    <div className="flex justify-between items-center bg-[#0b0f19] p-3 rounded-lg border border-slate-900">
                      <span className="text-xs text-slate-400">Protocol Driver</span>
                      <span className="text-xs font-mono font-bold px-2 py-0.5 bg-[#00f0ff]/10 text-[#00f0ff] rounded uppercase">
                        {activeServer.type}
                      </span>
                    </div>

                    <div className="flex justify-between items-center bg-[#0b0f19] p-3 rounded-lg border border-slate-900">
                      <span className="text-xs text-slate-400">Server IP/Host</span>
                      <span className="text-sm font-mono font-bold text-[#e2e8f0]">
                        {activeServer.address}
                      </span>
                    </div>

                    <div className="flex justify-between items-center bg-[#0b0f19] p-3 rounded-lg border border-slate-900">
                      <span className="text-xs text-slate-400">Connection Port</span>
                      <span className="text-sm font-mono text-[#e2e8f0]">
                        {activeServer.port}
                      </span>
                    </div>

                    <div className="bg-[#0b0f19] p-3 rounded-lg border border-slate-900">
                      <div className="text-xs text-slate-400 mb-2">UUID Client Identifier / Key</div>
                      <div className="text-xs font-mono text-slate-300 break-all bg-slate-950 p-2 rounded border border-slate-900 select-all">
                        {activeServer.uuid || 'None specified'}
                      </div>
                    </div>

                    <div className="flex justify-between items-center bg-[#0b0f19] p-3 rounded-lg border border-slate-900">
                      <span className="text-xs text-slate-400">Transport Network</span>
                      <span className="text-sm font-semibold uppercase text-slate-300">
                        {activeServer.network}
                      </span>
                    </div>

                    {activeServer.path && (
                      <div className="flex justify-between items-center bg-[#0b0f19] p-3 rounded-lg border border-slate-900">
                        <span className="text-xs text-slate-400">
                          {activeServer.network === 'ws' ? 'Websocket Path' : 'gRPC Service'}
                        </span>
                        <span className="text-sm font-mono text-slate-300 max-w-[180px] truncate" title={activeServer.path}>
                          {activeServer.path}
                        </span>
                      </div>
                    )}

                    <div className="flex justify-between items-center bg-[#0b0f19] p-3 rounded-lg border border-slate-900">
                      <span className="text-xs text-slate-400">TLS Encryption Sec</span>
                      <span className={`text-xs font-bold px-2 py-0.5 rounded ${
                        activeServer.tls 
                          ? 'bg-[#39ff14]/10 text-[#39ff14]' 
                          : 'bg-slate-800 text-slate-500'
                      }`}>
                        {activeServer.tls ? 'ENABLED' : 'DISABLED'}
                      </span>
                    </div>

                    {activeServer.tls && activeServer.sni && (
                      <div className="flex justify-between items-center bg-[#0b0f19] p-3 rounded-lg border border-slate-900">
                        <span className="text-xs text-slate-400">Server SNI host</span>
                        <span className="text-sm font-mono text-slate-300 truncate max-w-[180px]" title={activeServer.sni}>
                          {activeServer.sni}
                        </span>
                      </div>
                    )}

                    {/* Quick export share url */}
                    <div className="pt-4 border-t border-slate-800">
                      <button
                        onClick={() => {
                          // Quick format V2Ray VMess/VLess link mock generator
                          const link = `${activeServer.type.toLowerCase()}://${activeServer.uuid}@${activeServer.address}:${activeServer.port}?type=${activeServer.network}${activeServer.tls ? '&security=tls' : ''}#${encodeURIComponent(activeServer.name)}`;
                          navigator.clipboard.writeText(link);
                          alert('Copied secure share link connection URI to clipboard:\n' + link);
                        }}
                        className="w-full py-2.5 bg-slate-800 hover:bg-slate-700 hover:text-[#00f0ff] font-semibold text-xs rounded-lg transition-colors duration-200 border border-slate-700/80 hover:border-[#00f0ff]/45 flex items-center justify-center gap-2"
                      >
                        <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M8.684 13.342C8.886 12.938 9 12.482 9 12c0-.482-.114-.938-.316-1.342m0 2.684a3 3 0 110-2.684m0 2.684l6.632 3.316m-6.632-6l6.632-3.316m0 0a3 3 0 105.367-2.684 3 3 0 00-5.367 2.684zm0 9.316a3 3 0 105.368 2.684 3 3 0 00-5.368-2.684z"></path></svg>
                        Copy Share Connection URL
                      </button>
                    </div>

                  </div>
                );
              })()}
            </div>
          </aside>

        </div>
      </div>

      {/* FOOTER credit brand */}
      <footer className="mt-16 text-center text-xs text-slate-600 font-medium">
        V2Ray Dan System Manager Terminal. Synchronized client-side in localStorage.
      </footer>

      {/* FORM DIALOG MODAL Overlay */}
      {showFormModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/75 backdrop-blur-sm animate-fadeIn">
          <div className="bg-[#1e293b] border border-slate-800 rounded-2xl w-full max-w-lg overflow-hidden shadow-2xl relative">
            
            {/* Modal Header */}
            <div className="bg-slate-900/60 p-5 border-b border-slate-800 flex justify-between items-center">
              <div>
                <span className="text-xs font-bold text-slate-500 uppercase tracking-widest block mb-1">
                  CONFIG EDITOR
                </span>
                <h3 className="text-lg font-bold text-white">
                  {isEditing ? 'Modify Gateway Node' : 'Register New Gateway Node'}
                </h3>
              </div>
              <button
                onClick={() => setShowFormModal(false)}
                className="p-1 hover:bg-slate-800 rounded text-slate-500 hover:text-white transition"
              >
                <svg className="w-6 h-6" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M6 18L18 6M6 6l12 12"></path></svg>
              </button>
            </div>

            {/* Modal Form */}
            <form onSubmit={handleSubmit} className="p-6 space-y-4 max-h-[500px] overflow-y-auto">
              
              {/* Profile Name */}
              <div>
                <label className="block text-xs font-bold text-slate-400 uppercase tracking-wider mb-1.5">Profile Name</label>
                <input
                  type="text"
                  required
                  value={name}
                  onChange={(e) => setName(e.target.value)}
                  placeholder="Germany Premium HighSpeed Outbound"
                  className="w-full bg-[#0b0f19] border border-slate-850 hover:border-slate-800 focus:border-[#39ff14] focus:outline-none rounded-lg px-4 py-2 text-sm text-white transition"
                />
              </div>

              {/* Protocol Driver & Port */}
              <div className="grid grid-cols-2 gap-4">
                <div>
                  <label className="block text-xs font-bold text-slate-400 uppercase tracking-wider mb-1.5">Protocol</label>
                  <select
                    value={type}
                    onChange={(e) => setType(e.target.value)}
                    className="w-full bg-[#0b0f19] border border-slate-850 focus:border-[#39ff14] focus:outline-none rounded-lg px-3 py-2 text-sm text-white transition"
                  >
                    <option value="VLESS">VLESS (VMess 2.0)</option>
                    <option value="VMESS">VMess</option>
                    <option value="SHADOWSOCKS">Shadowsocks</option>
                    <option value="TROJAN">Trojan</option>
                  </select>
                </div>
                <div>
                  <label className="block text-xs font-bold text-slate-400 uppercase tracking-wider mb-1.5">Port</label>
                  <input
                    type="number"
                    required
                    value={port}
                    onChange={(e) => setPort(e.target.value)}
                    placeholder="443"
                    className="w-full bg-[#0b0f19] border border-slate-850 focus:border-[#39ff14] focus:outline-none rounded-lg px-4 py-2 text-sm text-white transition"
                  />
                </div>
              </div>

              {/* Server Host/IP Address */}
              <div>
                <label className="block text-xs font-bold text-slate-400 uppercase tracking-wider mb-1.5">Server Host address / IP</label>
                <input
                  type="text"
                  required
                  value={address}
                  onChange={(e) => setAddress(e.target.value)}
                  placeholder="de1.securenode.com"
                  className="w-full bg-[#0b0f19] border border-slate-850 focus:border-[#39ff14] focus:outline-none rounded-lg px-4 py-2 text-sm text-white font-mono transition"
                />
              </div>

              {/* UUID client id / Shadowsocks config password */}
              <div>
                <label className="block text-xs font-bold text-slate-400 uppercase tracking-wider mb-1.5">
                  {type === 'SHADOWSOCKS' || type === 'TROJAN' ? 'Password / Encryption Key' : 'UUID / Client Identifier'}
                </label>
                <input
                  type="text"
                  value={uuid}
                  onChange={(e) => setUuid(e.target.value)}
                  placeholder={type === 'SHADOWSOCKS' ? 'Crypto passphraseKey' : '8-4-4-4-12 UUID format'}
                  className="w-full bg-[#0b0f19] border border-slate-850 focus:border-[#39ff14] focus:outline-none rounded-lg px-4 py-2 text-sm text-white font-mono transition"
                />
              </div>

              {/* Transport Network & TLS options */}
              <div className="grid grid-cols-2 gap-4 items-center pt-2">
                <div>
                  <label className="block text-[#94a3b8] text-xs font-bold uppercase tracking-wider mb-1.5">Transport Network</label>
                  <select
                    value={network}
                    onChange={(e) => setNetwork(e.target.value)}
                    className="w-full bg-[#0b0f19] border border-slate-850 focus:border-[#39ff14] focus:outline-none rounded-lg px-3 py-2 text-sm text-white transition"
                  >
                    <option value="ws">Websocket (WS)</option>
                    <option value="tcp">TCP (Native)</option>
                    <option value="grpc">gRPC Protocol</option>
                  </select>
                </div>
                <div className="flex items-center gap-2 mt-4 select-none">
                  <input
                    type="checkbox"
                    id="tlsToggle"
                    checked={tls}
                    onChange={(e) => setTls(e.target.checked)}
                    className="w-4 h-4 text-[#39ff14] bg-[#0b0f19] border-slate-800 rounded focus:ring-0 accent-[#39ff14]"
                  />
                  <label htmlFor="tlsToggle" className="text-xs font-bold text-slate-400 cursor-pointer uppercase">
                    TLS Encryption Sec
                  </label>
                </div>
              </div>

              {/* Stream path (websocket or grpc path) */}
              {network !== 'tcp' && (
                <div>
                  <label className="block text-xs font-bold text-[#475569] uppercase tracking-wider mb-1.5">
                    {network === 'ws' ? 'Websocket Request Path' : 'gRPC Service Identifier'}
                  </label>
                  <input
                    type="text"
                    value={path}
                    onChange={(e) => setPath(e.target.value)}
                    placeholder={network === 'ws' ? '/vless-ws' : 'VmessGrpcService'}
                    className="w-full bg-[#0b0f19] border border-slate-850 focus:border-[#39ff14] focus:outline-none rounded-lg px-4 py-2 text-sm text-white font-mono transition"
                  />
                </div>
              )}

              {/* Server SNI Check (TLS override) */}
              {tls && (
                <div>
                  <label className="block text-xs font-bold text-slate-400 uppercase tracking-wider mb-1.5">Server SNI Hostname (Optional Override)</label>
                  <input
                    type="text"
                    value={sni}
                    onChange={(e) => setSni(e.target.value)}
                    placeholder="my-server-sni.hostname.com"
                    className="w-full bg-[#0b0f19] border border-slate-850 focus:border-[#39ff14] focus:outline-none rounded-lg px-4 py-2 text-sm text-white font-mono transition"
                  />
                </div>
              )}

              {/* Actions submit/cancel */}
              <div className="pt-4 flex justify-end gap-3 border-t border-slate-800">
                <button
                  type="button"
                  onClick={() => setShowFormModal(false)}
                  className="px-4 py-2 text-sm bg-slate-850 hover:bg-slate-800 hover:text-white rounded-lg text-slate-400 font-medium transition"
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  className="px-6 py-2 text-sm bg-[#39ff14] hover:bg-[#2ecc17] text-[#0b0f19] font-bold rounded-lg transition duration-200"
                >
                  {isEditing ? 'Update Settings' : 'Add Active Profile'}
                </button>
              </div>

            </form>
          </div>
        </div>
      )}

    </div>
  );
}
