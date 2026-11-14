function createAssetsController({ contracts }, name) {
  return (req, res) => {
    const asset = contracts.assets[name];
    res.set({ ETag: asset.etag, 'Cache-Control': 'private, no-cache' });
    // Express's freshness check handles comma-separated tags, weak tags and '*'.
    res.status(200).type('application/json');
    if (req.fresh) return res.status(304).end();
    res.send(asset.raw);
  };
}
module.exports = { createAssetsController };
