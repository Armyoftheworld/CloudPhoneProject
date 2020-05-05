import React from "react";
import SocketConstants from "./socketConstants";
import * as ControlMessage from "./controlMessage";
import "./App.css";

var message = require("./controlMessage_pb");

export default class App extends React.Component {
  constructor(props) {
    super(props);
    this.state = {
      width: 506,
      height: 900,
    };
  }

  configWebSocket = (socket, name, onReceivedData) => {
    socket.binaryType = "arraybuffer";
    socket.onmessage = (event) => {
      console.log(name + "\treceived data: ", event.data);
      onReceivedData(event.data);
    };

    socket.onopen = (event) => {
      console.log(name + "\t打开WebSoket 服务正常，浏览器支持WebSoket!");
    };

    socket.onclose = (event) => {
      console.log(name + "\tWebSocket 关闭");
    };

    socket.onerror = (event) => {
      console.log(name + "\tWebSocket error", event);
    };
  };

  handleControlMsg = (controlMessage) => {
    if (controlMessage.getType() === SocketConstants.TYPE_DEVICE_INFO) {
      this.originWidth = controlMessage.getWidth();
      this.originHeight = controlMessage.getHeight();
      console.log(
        "width = ",
        this.originWidth,
        "height = ",
        this.originHeight,
        "text = ",
        controlMessage.getText()
      );
      this.yuvCanvas = new global.YUVCanvas({
        canvas: this.refs.broadway_canvas,
        width: this.originWidth,
        height: this.originHeight,
      });
      this.ylen = this.originWidth * this.originHeight;
      this.uvlen = (this.originWidth / 2) * (this.originHeight / 2);
      this.setState({ width: this.originWidth, height: this.originHeight });
    }
  };

  componentDidMount() {
    this.controlSocket = new WebSocket(
      "ws://localhost:" + SocketConstants.PORT
    );
    this.configWebSocket(this.controlSocket, "controlSocket", (data) => {
      const controlMessage = message.ControlMessage.deserializeBinary(data);
      console.log("controlMessage = ", controlMessage);
      this.handleControlMsg(controlMessage);
    });

    this.videoSocket = new WebSocket("ws://localhost:" + SocketConstants.PORT);
    this.configWebSocket(this.videoSocket, "videoSocket", (data) => {
      if (this.yuvCanvas) {
        const yuvData = new Uint8Array(data);
        this.yuvCanvas.drawNextOutputPicture({
          yData: yuvData.subarray(0, this.ylen),
          uData: yuvData.subarray(this.ylen, this.ylen + this.uvlen),
          vData: yuvData.subarray(this.ylen + this.uvlen, this.ylen + this.uvlen + this.uvlen)
        });
      }
    });
    // chrome添加鼠标滚轮滚动事件
    this.refs.broadway_canvas.addEventListener(
      "mousewheel",
      (e) => this.onMouseWheel(e, true),
      false
    );
    this.refs.broadway_canvas.addEventListener(
      "DOMMouseScroll",
      (e) => this.onMouseWheel(e, false),
      false
    );
  }

  componentWillUnmount() {
    if (this.controlSocket != null) {
      this.controlSocket.close();
    }
    if (this.videoSocket != null) {
      this.videoSocket.close();
    }
  }

  onMouseWheel = (e, isChrome) => {
    console.log("onMouseWheel = ", e.deltaY || e.detail);
  };

  createTouchEventParams = (event) => {
    const [offsetLeft, offsetTop] = this.getOffsetLeftTop()
    const params = {
      width: this.originWidth,
      height: this.originHeight,
      x: Math.floor((event.x - offsetLeft) * (this.scaleRadio || 1)),
      y: Math.floor((event.y - offsetTop) * (this.scaleRadio || 1)),
      buttons: event.button,
    };
    console.log("params = ", params);
    return params;
  };

  getOffsetLeftTop = () => {
    const ele = this.refs.broadway_canvas;
    let offsetLeft = ele.offsetLeft;
    let offsetTop = ele.offsetTop;
    let offsetParent = ele.offsetParent;
    while (offsetParent) {
      offsetLeft += offsetParent.offsetLeft;
      offsetTop += offsetParent.offsetTop;
      offsetParent = offsetParent.offsetParent;
    }
    return [offsetLeft, offsetTop];
  }

  οnMοuseDοwn = (e) => {
    this.isMouseDown = true;
    const event = e.nativeEvent;
    console.log("οnMοuseDοwn = ", event);
    let controlMessage;
    if (event.button == SocketConstants.MOUSE_LEFT) {
      // 左击
      controlMessage = ControlMessage.createTouchDownMessage(
        this.createTouchEventParams(event)
      );
    } else if (event.button == SocketConstants.MOUSE_MIDDLE) {
      // 点击滚轮
    } else if (event.button == SocketConstants.MOUSE_RIGHT) {
      // 右击
      controlMessage = ControlMessage.createBackOrScreenOn();
    }
    if (controlMessage && this.controlSocket != null) {
      const data = controlMessage.serializeBinary();
      console.log("οnMοuseDοwn data.length = ", data.length);
      this.controlSocket.send(data);
    }
  };

  οnMοuseUp = (e) => {
    this.isMouseDown = false;
    const event = e.nativeEvent;
    console.log("οnMοuseUp = ", event);
    console.log(this.controlSocket);
    if (this.controlSocket != null) {
      const params = this.createTouchEventParams(event);
      const data = ControlMessage.createTouchUpMessage(params).serializeBinary();
      console.log("οnMοuseUp data.length = ", data.length);
      this.controlSocket.send(data);
    }
  };

  onMouseMove = (e) => {
    if (!this.isMouseDown) {
      return;
    }
    const event = e.nativeEvent;
    console.log("onMouseMove = ", event);
    if (this.controlSocket) {
      const params = this.createTouchEventParams(event);
      this.controlSocket.send(
        ControlMessage.createTouchMoveMessage(params).serializeBinary()
      );
    }
  };

  onMouseOut = (e) => {
    if (!this.isMouseDown) {
      return;
    }
    this.isMouseDown = false;
    const event = e.nativeEvent;
    console.log("onMouseOut = ", event);
    // 当成 up 事件处理
    if (this.controlSocket) {
      const params = this.createTouchEventParams(event);
      this.controlSocket.send(
        ControlMessage.createTouchUpMessage(params).serializeBinary()
      );
    }
  };

  render() {
    return (
      <div className="App">
        <canvas
          ref="broadway_canvas"
          id="broadway_canvas"
          style={{
            width: this.state.width,
            height: this.state.height,
          }}
          onMouseDown={this.οnMοuseDοwn}
          onMouseUp={this.οnMοuseUp}
          onContextMenu={(e) => e.preventDefault()}
          onMouseMove={this.onMouseMove}
          onMouseOut={this.onMouseOut}
        />
      </div>
    );
  }
}
