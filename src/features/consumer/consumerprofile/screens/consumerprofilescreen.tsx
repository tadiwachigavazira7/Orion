import { supabase } from '@/src/lib/supabase';
import React, { useEffect, useState } from 'react';
import { StyleSheet, View } from 'react-native';
import { theme } from '../../../../theme';
import { ConsumerProfileSlider } from '../components/consumerprofileslider';
import { ProfileSliderPanel } from '../components/consumerprofilesliderpanel';

export default function ConsumerProfileScreen() {

  useEffect(() => {
    const testFetch = async () => {
      const { data, error } = await supabase
        .from('payment_methods')
        .select('*');

      console.log('DATA:', data);
      console.log('ERROR:', error);
    };

    testFetch();
  }, []);

  



  // Tracking the active tab state explicitly
  const [activeTab, setActiveTab] = useState<'Edit Profile' | 'Payment Methods' | 'Purchases'>('Edit Profile');
  
  // Shared profile identity states passed into the dashboard form panels
  const [firstName, setFirstName] = useState('James');
  const [lastName, setLastName] = useState('');

  // Normalize full header tab strings into concise selector keys for the panel contents
  const getActivePanelKey = () => {
    switch (activeTab) {
      case 'Edit Profile': return 'profile';
      case 'Payment Methods': return 'payment';
      case 'Purchases': return 'purchases';
      default: return 'profile';
    }
  };

  return (
    <View style={[styles.container, { backgroundColor: theme.background }]}>
      {/* Dynamic Navigation Header Tab Controller */}
      <ConsumerProfileSlider 
        activeTab={activeTab} 
        onTabChange={(tab: any) => setActiveTab(tab)} 
        firstName={firstName}
        lastName={lastName}
      />

      {/* Main Dynamic Panel Canvas Body */}
      <View style={styles.contentBody}>
        <ProfileSliderPanel 
          activeTab={getActivePanelKey()}
          firstName={firstName}
          setFirstName={setFirstName}
          lastName={lastName}
          setLastName={setLastName}
        />
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
  },
  contentBody: {
    flex: 1,
    marginTop: 20,
  },
});