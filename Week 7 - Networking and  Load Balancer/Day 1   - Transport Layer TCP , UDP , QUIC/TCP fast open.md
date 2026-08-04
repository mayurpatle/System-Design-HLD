## TCP Fast open to reduce the latency of TCP connections  

Loading a webpage often requires fetching hundreds of resources from dozens of different hosts. In turn, this might require the browser to establish dozens of new TCP connections, each of which will have to incur the overhead of the TCP handshake. Needless to say, this can be a significant source of web browsing latency, especially on slower mobile networks.

TCP Fast Open (TFO) is a mechanism that aims to eliminate the latency penalty imposed on new TCP connections by allowing data transfer within the SYN packet. However, it does have its own set of limitations: there are limits on the maximum size of the data payload within the SYN packet, only certain types of HTTP requests can be sent, and it works only for repeat connections due to a requirement for a cryptographic cookie. For a detailed discussion on the capabilities and limitations of TFO, check the latest IETF draft of "TCP Fast Open."

Enabling TFO requires explicit support on the client, server, and opt-in from the application. For best results, use the Linux kernel v4.1+ on the server, a compatible client (e.g. Linux, or iOS9+ / OSX 10.11+), and enable the appropriate socket flags within your application.

Based on traffic analysis and network emulation done at Google, researchers have shown that TFO can decrease HTTP transaction network latency by 15%, whole-page load times by over 10% on average, and in some cases by up to 40% in high-latency scenarios!