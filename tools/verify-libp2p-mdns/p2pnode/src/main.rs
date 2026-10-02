//! A libp2p node with mDNS switched on, configured as the desktop's is
//! (`ac-net/src/swarm.rs`: `mdns::Config::default()`), for `Libp2pMdnsTest` to
//! find with the companion app's own lookup.
//!
//! Prints `peer <id>`, then `listening <multiaddr>` for each address it takes,
//! and runs until its stdin closes — so it never outlives the test that started
//! it, however that test ends.
use libp2p::futures::StreamExt;
use libp2p::{mdns, noise, swarm::SwarmEvent, tcp, yamux};
use tokio::io::AsyncReadExt;

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let mut swarm = libp2p::SwarmBuilder::with_new_identity()
        .with_tokio()
        .with_tcp(tcp::Config::default(), noise::Config::new, yamux::Config::default)?
        .with_behaviour(
            |key| -> Result<mdns::tokio::Behaviour, Box<dyn std::error::Error + Send + Sync>> {
                Ok(mdns::tokio::Behaviour::new(
                    mdns::Config::default(),
                    key.public().to_peer_id(),
                )?)
            },
        )?
        .build();

    println!("peer {}", swarm.local_peer_id());
    swarm.listen_on("/ip4/0.0.0.0/tcp/0".parse()?)?;

    let mut stdin = tokio::io::stdin();
    let mut sink = [0u8; 64];
    loop {
        tokio::select! {
            event = swarm.select_next_some() => {
                if let SwarmEvent::NewListenAddr { address, .. } = event {
                    println!("listening {address}");
                }
            }
            read = stdin.read(&mut sink) => {
                if matches!(read, Ok(0) | Err(_)) {
                    return Ok(());
                }
            }
        }
    }
}
