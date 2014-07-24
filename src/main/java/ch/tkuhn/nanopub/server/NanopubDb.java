package ch.tkuhn.nanopub.server;

import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import net.trustyuri.TrustyUriUtils;

import org.apache.commons.lang3.tuple.Pair;
import org.nanopub.MalformedNanopubException;
import org.nanopub.Nanopub;
import org.nanopub.NanopubImpl;
import org.nanopub.NanopubUtils;
import org.nanopub.NanopubWithNs;
import org.nanopub.extra.server.ServerInfo.ServerInfoException;
import org.nanopub.trusty.TrustyNanopubUtils;
import org.openrdf.OpenRDFException;
import org.openrdf.rio.RDFFormat;
import org.openrdf.rio.RDFHandlerException;

import com.mongodb.BasicDBObject;
import com.mongodb.DB;
import com.mongodb.DBCollection;
import com.mongodb.DBCursor;
import com.mongodb.DBObject;
import com.mongodb.MongoClient;

public class NanopubDb {

	public static class NotTrustyNanopubException extends Exception {

		private static final long serialVersionUID = -3782872539656552144L;

		public NotTrustyNanopubException(Nanopub np) {
			super(np.getUri().toString());
		}

	}

	// Use trig internally to keep namespaces:
	private static RDFFormat internalFormat = RDFFormat.TRIG;

	private static NanopubDb obj;

	public static NanopubDb get() {
		if (obj == null) {
			try {
				obj = new NanopubDb();
			} catch (UnknownHostException ex) {
				ex.printStackTrace();
				System.exit(1);
			}
		}
		return obj;
	}

	private ServerConf conf;
	private MongoClient mongo;
	private DB db;
	private int pageSize;

	private NanopubDb() throws UnknownHostException {
		conf = ServerConf.get();
		mongo = new MongoClient(conf.getMongoDbHost(), conf.getMongoDbPort());
		db = mongo.getDB(conf.getMongoDbName());
		init();
	}

	private void init() {
		for (String s : conf.getInitialPeers()) {
			addPeerToCollection(s);
		}
		if (!db.getCollectionNames().contains("journal")) {
			setJournalField("journal-id", Math.abs(new Random().nextLong()) + "");
			setJournalField("next-nanopub-no", "0");
			setJournalField("page-size", ServerConf.get().getInitPageSize() + "");
		}
		pageSize = Integer.parseInt(getJournalField("page-size"));
	}

	private String getJournalField(String field) {
		BasicDBObject query = new BasicDBObject("_id", field);
		DBCursor cursor = getJournalCollection().find(query);
		if (cursor.hasNext()) {
			return cursor.next().get("value").toString();
		} else {
			return null;
		}
	}

	private void setJournalField(String field, String value) {
		BasicDBObject dbObj = new BasicDBObject("_id", field).append("value", value);
		getJournalCollection().save(dbObj);
	}

	public MongoClient getMongoClient() {
		return mongo;
	}

	private DBCollection getNanopubCollection() {
		return db.getCollection("nanopubs");
	}

	private DBCollection getJournalCollection() {
		return db.getCollection("journal");
	}

	public Nanopub getNanopub(String artifactCode) {
		BasicDBObject query = new BasicDBObject("_id", artifactCode);
		DBCursor cursor = getNanopubCollection().find(query);
		if (!cursor.hasNext()) {
			return null;
		}
		String nanopubString = cursor.next().get("nanopub").toString();
		Nanopub np = null;
		try {
			np = new NanopubImpl(nanopubString, internalFormat);
		} catch (MalformedNanopubException ex) {
			throw new RuntimeException("Stored nanopub is not wellformed (this shouldn't happen)", ex);
		} catch (OpenRDFException ex) {
			throw new RuntimeException("Stored nanopub is corrupted (this shouldn't happen)", ex);
		}
		if (!TrustyNanopubUtils.isValidTrustyNanopub(np)) {
			throw new RuntimeException("Stored nanopub is not trusty (this shouldn't happen)");
		}
		return np;
	}

	public boolean hasNanopub(String artifactCode) {
		BasicDBObject query = new BasicDBObject("_id", artifactCode);
		return getNanopubCollection().find(query).hasNext();
	}

	public synchronized void loadNanopub(Nanopub np) throws NotTrustyNanopubException {
		if (np instanceof NanopubWithNs) {
			((NanopubWithNs) np).removeUnusedPrefixes();
		}
		if (!TrustyNanopubUtils.isValidTrustyNanopub(np)) {
			throw new NotTrustyNanopubException(np);
		}
		String artifactCode = TrustyUriUtils.getArtifactCode(np.getUri().toString());
		String npString = null;
		try {
			npString = NanopubUtils.writeToString(np, internalFormat);
		} catch (RDFHandlerException ex) {
			throw new RuntimeException("Unexpected exception when processing nanopub", ex);
		}
		BasicDBObject id = new BasicDBObject("_id", artifactCode);
		BasicDBObject dbObj = new BasicDBObject("_id", artifactCode).append("nanopub", npString).append("uri", np.getUri().toString());
		DBCollection coll = getNanopubCollection();
		if (!coll.find(id).hasNext()) {
			coll.insert(dbObj);
			addToJournal(np);
		}
	}

	private void addToJournal(Nanopub np) {
		long currentPageNo = getCurrentPageNo();
		String pageContent = getPageContent(currentPageNo);
		pageContent += np.getUri() + "\n";
		setPageContent(currentPageNo, pageContent);
		long nextNanopubNo = getNextNanopubNo() + 1;
		setJournalField("next-nanopub-no", "" + nextNanopubNo);
	}

	public long getCurrentPageNo() {
		return getNextNanopubNo()/getPageSize() + 1;
	}

	private void setPageContent(long pageNo, String pageContent) {
		setJournalField("page" + pageNo, pageContent);
	}

	public String getPageContent(long pageNo) {
		String pageName = "page" + pageNo;
		String pageContent = getJournalField(pageName);
		if (pageContent == null) {
			if (getNextNanopubNo() % getPageSize() > 0) {
				throw new RuntimeException("Cannot find journal page: " + pageName);
			}
			// Make new page
			pageContent = "";
		}
		return pageContent;
	}

	public DBCollection getPeerCollection() {
		return db.getCollection("peers");
	}

	public List<String> getPeerUris() {
		List<String> peers = new ArrayList<String>();
		DBCursor cursor = getPeerCollection().find();
		while (cursor.hasNext()) {
			peers.add(cursor.next().get("_id").toString());
		}
		return peers;
	}

	public void addPeer(String peerUrl) throws ServerInfoException {
		ServerInfo.load(peerUrl);  // throw exception if something is wrong
		addPeerToCollection(peerUrl);
	}

	private void addPeerToCollection(String peerUrl) {
		if (peerUrl.equals(ServerConf.getInfo().getPublicUrl())) {
			return;
		}
		DBCollection coll = getPeerCollection();
		BasicDBObject dbObj = new BasicDBObject("_id", peerUrl);
		if (!coll.find(dbObj).hasNext()) {
			coll.insert(dbObj);
		}
	}

	public void updatePeerState(ServerInfo peerInfo, long npno) {
		String url = peerInfo.getPublicUrl();
		BasicDBObject q = new BasicDBObject("_id", url);
		long jid = peerInfo.getJournalId();
		BasicDBObject update = new BasicDBObject("_id", url).append("journalId", jid).append("nextNanopubNo", npno);
		getPeerCollection().update(q, update);
	}

	public Pair<Long,Long> getLastSeenPeerState(String peerUrl) {
		BasicDBObject q = new BasicDBObject("_id", peerUrl);
		Long journalId = null;
		Long nextNanopubNo = null;
		DBObject r = getPeerCollection().find(q).next();
		if (r.containsField("journalId")) journalId = Long.parseLong(r.get("journalId").toString());
		if (r.containsField("nextNanopubNo")) nextNanopubNo = Long.parseLong(r.get("nextNanopubNo").toString());
		if (journalId == null || nextNanopubNo == null) return null;
		return Pair.of(journalId, nextNanopubNo);
	}

	public long getJournalId() {
		return Long.parseLong(getJournalField("journal-id"));
	}

	public synchronized long getNextNanopubNo() {
		return Long.parseLong(getJournalField("next-nanopub-no"));
	}

	public String getJournalStateId() {
		return getJournalId() + "/" + getNextNanopubNo();
	}

	public int getPageSize() {
		return pageSize;
	}

}
