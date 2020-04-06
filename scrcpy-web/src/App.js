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

  getRealSize = (screenWidth, screenHeight) => {
    if (
      screenHeight > screenWidth &&
      screenHeight >= SocketConstants.CANVAS_MAX_SIZE
    ) {
      this.scaleRadio = screenHeight / SocketConstants.CANVAS_MAX_SIZE;
      return [
        (screenWidth * SocketConstants.CANVAS_MAX_SIZE) / screenHeight,
        SocketConstants.CANVAS_MAX_SIZE,
      ];
    }
    if (
      screenWidth > screenHeight &&
      screenWidth >= SocketConstants.CANVAS_MAX_SIZE
    ) {
      this.scaleRadio = screenWidth / SocketConstants.CANVAS_MAX_SIZE;
      return [
        SocketConstants.CANVAS_MAX_SIZE,
        (screenHeight * SocketConstants.CANVAS_MAX_SIZE) / screenWidth,
      ];
    }
    this.scaleRadio = 1;
    return [screenWidth, screenHeight];
  };

  handleControlMsg = (controlMessage) => {
    if (controlMessage.getType() === SocketConstants.TYPE_DEVICE_INFO) {
      this.originWidth = controlMessage.getWidth();
      this.originHeight = controlMessage.getHeight();
      const [width, height] = this.getRealSize(
        this.originWidth,
        this.originHeight
      );
      console.log(
        "width = ",
        this.originHeight,
        "height = ",
        this.originHeight,
        "text = ",
        this.originHeight
      );
      this.player = new global.Player({
        size: { width, height },
      });
      this.setState({ width, height });
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
      if (this.player) {
        this.player.decode(new Uint8Array(data));
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
    //   if(e.button ==2){
    //     alert("你点了右键");
    // }else if(e.button ==0){
    //     alert("你点了左键");
    // }else if(e.button ==1){
    //     alert("你点了滚轮");
    // }
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

  // onMouseLeave = (e) => {
  //   if (this.isMouseDown) {
  //     console.log("onMouseLeave = ", e.nativeEvent);
  //     this.isMouseDown = false;
  //   }
  // };

  // onMouseEnter = (e) => {
  //   console.log("onMouseEnter = ", e.nativeEvent);
  // };

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

  // onMouseOver = (e) => {
  //   console.log("onMouseOver = ", e.nativeEvent);
  // };

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
          // onMouseLeave={this.onMouseLeave}
          // onMouseEnter={this.onMouseEnter}
          onMouseOut={this.onMouseOut}
          // onMouseOver={this.onMouseOver}
        />
      </div>
    );
  }
}
